package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.SpellSlot;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.PlayerRuntimeState;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.repository.PlayerRuntimeStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative owner of per-session runtime state (HP, spell slots, inventory,
 * conditions). Every mutation clamps to valid ranges. Never touches the
 * {@code Character} template.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerStateService {

    /** D&D 5e Potion of Healing. */
    private static final String HEALING_POTION_DICE = "2d4+2";

    private final PlayerRuntimeStateRepository repository;
    private final DiceService diceService;
    private final PlayerStateSeeder seeder;

    /** Outcome of consuming an item, including any heal roll for narration/animation. */
    public record ItemUseResult(
            String itemName,
            ItemKind kind,
            DiceRollResult healRoll,
            int healed,
            PlayerRuntimeStateDto state
    ) {}

    /** What a single death saving throw did to the creature's fate. */
    public enum DeathSaveOutcome { SUCCESS, FAILURE, REVIVED, STABILIZED, DIED }

    /** Result of recording a death save: the updated state plus what it resolved to. */
    public record DeathSaveResult(PlayerRuntimeStateDto state, DeathSaveOutcome outcome) {}

    /* ── seeding (delegated to PlayerStateSeeder) ─────────────────── */

    /**
     * Ensure a runtime state row exists for the player, seeding it if missing. Delegates to
     * {@link PlayerStateSeeder#ensureSeeded(Player, Character)}; kept here so existing callers
     * (combat) keep a single entry point for runtime state.
     */
    public void ensureSeeded(Player player, Character character) {
        seeder.ensureSeeded(player, character);
    }

    /** Seed runtime state from a character template. Delegates to {@link PlayerStateSeeder#seedForPlayer(Player, Character)}. */
    public void seedForPlayer(Player player, Character character) {
        seeder.seedForPlayer(player, character);
    }

    /* ── reads ───────────────────────────────────────────────────── */

    public PlayerRuntimeStateDto getState(UUID playerId) {
        return toDto(require(playerId));
    }

    public List<PlayerRuntimeStateDto> getSessionStates(UUID sessionId) {
        return repository.findBySessionId(sessionId).stream().map(this::toDto).toList();
    }

    /* ── mutations ───────────────────────────────────────────────── */

    /** Apply a manual HP delta (positive heals, negative damages). Returns new state. */
    @Transactional
    public PlayerRuntimeStateDto applyHpDelta(UUID playerId, int amount) {
        return applyHpDelta(playerId, amount, false);
    }

    /**
     * Apply an HP delta where damage may carry a critical flag (matters at 0 HP, where a critical
     * hit inflicts two death-save failures and can deal instant death). Positive amounts heal and
     * ignore {@code critical}.
     */
    @Transactional
    public PlayerRuntimeStateDto applyHpDelta(UUID playerId, int amount, boolean critical) {
        PlayerRuntimeState s = require(playerId);
        if (amount >= 0) {
            DyingRules.heal(s, amount);
        } else {
            DyingRules.damage(s, -amount, critical);
        }
        return toDto(repository.save(s));
    }

    /**
     * Record one death saving throw (raw d20, no modifiers) for a dying creature and return the
     * updated state plus the outcome. Natural 20 revives at 1 HP; natural 1 is two failures;
     * otherwise 10+ is a success and 9 or less a failure. Three successes stabilize; three failures
     * kill.
     */
    @Transactional
    public DeathSaveResult recordDeathSave(UUID playerId, DiceRollResult roll) {
        PlayerRuntimeState s = require(playerId);
        DeathSaveOutcome outcome = DyingRules.recordDeathSave(s, roll);
        return new DeathSaveResult(toDto(repository.save(s)), outcome);
    }

    /**
     * Stabilize a dying creature (e.g. a successful DC 10 Medicine check): it stays at 0 HP and
     * unconscious but stops rolling death saves. Idempotent when already stable; rejects targets
     * that aren't dying.
     */
    @Transactional
    public PlayerRuntimeStateDto stabilize(UUID playerId) {
        PlayerRuntimeState s = require(playerId);
        if (DyingRules.stabilize(s)) {
            s = repository.save(s);
        }
        return toDto(s);
    }

    /** Spend a spell slot of the given level. Throws if none available. */
    @Transactional
    public PlayerRuntimeStateDto useSpellSlot(UUID playerId, int level) {
        PlayerRuntimeState s = require(playerId);
        List<SpellSlot> slots = s.getSpellSlots();
        for (int i = 0; i < slots.size(); i++) {
            SpellSlot slot = slots.get(i);
            if (slot.level() == level) {
                if (slot.used() >= slot.max()) {
                    throw new IllegalStateException("No level-" + level + " spell slots remaining");
                }
                slots.set(i, new SpellSlot(slot.level(), slot.max(), slot.used() + 1));
                return toDto(repository.save(s));
            }
        }
        throw new IllegalStateException("No level-" + level + " spell slots for this character");
    }

    /** Consume one of the named item. Healing potions roll {@value #HEALING_POTION_DICE}. */
    @Transactional
    public ItemUseResult useItem(UUID playerId, String itemName) {
        PlayerRuntimeState s = require(playerId);
        List<InventoryItem> inv = s.getInventory();

        int idx = -1;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).name().equalsIgnoreCase(itemName) && inv.get(i).qty() > 0) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalStateException("Item not in inventory: " + itemName);
        }

        InventoryItem item = inv.get(idx);
        if (item.qty() <= 1) {
            inv.remove(idx);
        } else {
            inv.set(idx, new InventoryItem(item.name(), item.qty() - 1, item.kind(), item.equipped()));
        }

        DiceRollResult healRoll = null;
        int healed = 0;
        if (item.kind() == ItemKind.POTION_HEALING) {
            healRoll = diceService.roll(HEALING_POTION_DICE);
            healed = DyingRules.heal(s, healRoll.total());
        }

        PlayerRuntimeStateDto dto = toDto(repository.save(s));
        return new ItemUseResult(item.name(), item.kind(), healRoll, healed, dto);
    }

    /** Add (or stack) an item — used by loot/combat rewards. */
    @Transactional
    public PlayerRuntimeStateDto addItem(UUID playerId, InventoryItem toAdd) {
        PlayerRuntimeState s = require(playerId);
        List<InventoryItem> inv = s.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            InventoryItem it = inv.get(i);
            if (it.name().equalsIgnoreCase(toAdd.name()) && it.kind() == toAdd.kind()) {
                inv.set(i, new InventoryItem(it.name(), it.qty() + toAdd.qty(), it.kind(), it.equipped()));
                return toDto(repository.save(s));
            }
        }
        inv.add(toAdd);
        return toDto(repository.save(s));
    }

    /** Drop one of the named item (decrement, removing the stack at zero). */
    @Transactional
    public PlayerRuntimeStateDto dropItem(UUID playerId, String itemName) {
        PlayerRuntimeState s = require(playerId);
        List<InventoryItem> inv = s.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            InventoryItem it = inv.get(i);
            if (it.name().equalsIgnoreCase(itemName) && it.qty() > 0) {
                if (it.qty() <= 1) {
                    inv.remove(i);
                } else {
                    inv.set(i, new InventoryItem(it.name(), it.qty() - 1, it.kind(), it.equipped()));
                }
                return toDto(repository.save(s));
            }
        }
        throw new IllegalStateException("Item not in inventory: " + itemName);
    }

    /** Toggle the equipped flag on a weapon/armor item. Display/context only — no AC change. */
    @Transactional
    public PlayerRuntimeStateDto equipItem(UUID playerId, String itemName, boolean equipped) {
        PlayerRuntimeState s = require(playerId);
        List<InventoryItem> inv = s.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            InventoryItem it = inv.get(i);
            if (it.name().equalsIgnoreCase(itemName)) {
                if (it.kind() != ItemKind.WEAPON && it.kind() != ItemKind.ARMOR) {
                    throw new IllegalStateException("Only weapons and armor can be equipped: " + itemName);
                }
                inv.set(i, new InventoryItem(it.name(), it.qty(), it.kind(), equipped));
                return toDto(repository.save(s));
            }
        }
        throw new IllegalStateException("Item not in inventory: " + itemName);
    }

    /**
     * Award Inspiration to a player (idempotent — setting an already-inspired player is a no-op).
     * Returns the updated state so callers can broadcast without a second read.
     */
    @Transactional
    public PlayerRuntimeStateDto grantInspiration(UUID playerId) {
        PlayerRuntimeState s = require(playerId);
        if (!s.isInspiration()) {
            s.setInspiration(true);
            s = repository.save(s);
        }
        return toDto(s);
    }

    /**
     * Spend a player's Inspiration if they hold it. Returns {@code true} when it was set (and now
     * cleared), {@code false} when the player had none — so the caller only folds advantage into
     * the roll when inspiration was actually consumed.
     */
    @Transactional
    public boolean consumeInspiration(UUID playerId) {
        PlayerRuntimeState s = require(playerId);
        if (!s.isInspiration()) {
            return false;
        }
        s.setInspiration(false);
        repository.save(s);
        return true;
    }

    /**
     * Long rest: recover all spell slots and clear conditions. A living (or merely dying/stable)
     * creature heals to full and resets its death-save state; a dead creature stays dead — a long
     * rest does not raise the dead.
     */
    @Transactional
    public PlayerRuntimeStateDto longRest(UUID playerId) {
        PlayerRuntimeState s = require(playerId);
        List<SpellSlot> refreshed = new ArrayList<>();
        for (SpellSlot slot : s.getSpellSlots()) {
            refreshed.add(new SpellSlot(slot.level(), slot.max(), 0));
        }
        s.setSpellSlots(refreshed);
        s.getConditions().clear();
        s.setConcentratingSpell(null);
        if (!s.isDead()) {
            s.setCurrentHp(s.getMaxHp());
            s.setTempHp(0);
            s.setStable(false);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
        }
        return toDto(repository.save(s));
    }

    /* ── conditions / concentration ──────────────────────────────── */

    /** A player's structured conditions (defensive copy; empty when none). */
    public List<ActiveCondition> conditions(UUID playerId) {
        return new ArrayList<>(require(playerId).getConditions());
    }

    /** Grant temporary HP (5E temp HP doesn't stack — take the higher). Returns updated state. */
    @Transactional
    public PlayerRuntimeStateDto applyTempHp(UUID playerId, int amount) {
        PlayerRuntimeState s = require(playerId);
        s.setTempHp(Math.max(s.getTempHp(), Math.max(0, amount)));
        return toDto(repository.save(s));
    }

    /** Apply (or replace by name) a condition on a player. Returns the updated state. */
    @Transactional
    public PlayerRuntimeStateDto applyCondition(UUID playerId, ActiveCondition c) {
        PlayerRuntimeState s = require(playerId);
        s.getConditions().removeIf(x -> x.name() != null && x.name().equalsIgnoreCase(c.name()));
        s.getConditions().add(c);
        return toDto(repository.save(s));
    }

    /** Record the concentration spell a player is now sustaining (null clears it). */
    @Transactional
    public PlayerRuntimeStateDto setConcentratingSpell(UUID playerId, String spell) {
        PlayerRuntimeState s = require(playerId);
        s.setConcentratingSpell(spell);
        return toDto(repository.save(s));
    }

    /**
     * Break a caster's concentration across every player in the session: clear the caster's
     * own concentration flag and drop every concentration-flagged condition they applied.
     * Returns the players whose state changed (so the caller can broadcast each).
     */
    @Transactional
    public List<PlayerRuntimeStateDto> breakConcentration(UUID sessionId, UUID casterId) {
        List<PlayerRuntimeStateDto> changed = new ArrayList<>();
        for (PlayerRuntimeState s : repository.findBySessionId(sessionId)) {
            boolean mutated = false;
            if (s.getPlayerId().equals(casterId) && s.getConcentratingSpell() != null) {
                s.setConcentratingSpell(null);
                mutated = true;
            }
            if (s.getConditions().removeIf(c -> c.concentration() && casterId.equals(c.sourceCasterId()))) {
                mutated = true;
            }
            if (mutated) {
                changed.add(toDto(repository.save(s)));
            }
        }
        return changed;
    }

    /**
     * Drop a player's timer-expired conditions (those whose {@code expiresAtRound} is strictly
     * before {@code round}). Returns the updated state when anything changed, else {@code null}.
     */
    @Transactional
    public PlayerRuntimeStateDto expireConditions(UUID playerId, int round) {
        PlayerRuntimeState s = require(playerId);
        boolean changed = s.getConditions().removeIf(
                c -> c.expiresAtRound() != null && round > c.expiresAtRound());
        return changed ? toDto(repository.save(s)) : null;
    }

    /** Clear all conditions and concentration for every player in a session (e.g. combat ends). */
    @Transactional
    public List<PlayerRuntimeStateDto> clearConditionsAndConcentration(UUID sessionId) {
        List<PlayerRuntimeStateDto> changed = new ArrayList<>();
        for (PlayerRuntimeState s : repository.findBySessionId(sessionId)) {
            if (!s.getConditions().isEmpty() || s.getConcentratingSpell() != null) {
                s.getConditions().clear();
                s.setConcentratingSpell(null);
                changed.add(toDto(repository.save(s)));
            }
        }
        return changed;
    }

    /* ── internals ───────────────────────────────────────────────── */

    private PlayerRuntimeState require(UUID playerId) {
        return repository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "No runtime state for player: " + playerId));
    }

    private PlayerRuntimeStateDto toDto(PlayerRuntimeState s) {
        // Effective AC includes any active AC-buff conditions (Mage Armor / Shield of Faith /
        // Barkskin), derived from the base AC so it reverts automatically when they end.
        Integer dex = s.getAbilities() == null ? null : s.getAbilities().get("DEX");
        int dexMod = dex == null ? 0 : Math.floorDiv(dex - 10, 2);
        int effectiveAc = ConditionRules.acAdjust(s.getConditions(), s.getArmorClass(), dexMod);
        return new PlayerRuntimeStateDto(
                s.getPlayerId(),
                s.getCurrentHp(),
                s.getMaxHp(),
                s.getTempHp(),
                effectiveAc,
                s.getAbilities(),
                s.getSpellSlots(),
                s.getInventory(),
                s.getConditions().stream().map(ActiveCondition::name).toList(),
                s.getCantrips(),
                s.getKnownSpells(),
                s.isInspiration(),
                s.getDeathSaveSuccesses(),
                s.getDeathSaveFailures(),
                s.isStable(),
                s.isDead(),
                s.getConcentratingSpell());
    }
}
