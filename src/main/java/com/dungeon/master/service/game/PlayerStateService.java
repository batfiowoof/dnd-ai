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
import com.dungeon.master.model.enums.EquipSlot;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.repository.PlayerRuntimeStateRepository;
import com.dungeon.master.service.game.combat.CombatMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * Apply a level-up to a player's LIVE runtime state from their (already-advanced) character
     * template: raise max HP to the new value and credit the gain to current HP, refresh spell-slot
     * maximums for the new level while preserving spent slots (a milestone is not a rest), and
     * re-snapshot the ability scores. Proficiency needs no work here — combat math reads it from the
     * Character template. Called by the milestone flow after {@code CharacterService.applyMilestoneLevel}.
     */
    @Transactional
    public PlayerRuntimeStateDto applyLevelUpToRuntime(UUID playerId, Character c) {
        PlayerRuntimeState s = require(playerId);
        int gained = Math.max(0, c.getHitPoints() - s.getMaxHp());
        s.setMaxHp(c.getHitPoints());
        s.setCurrentHp(Math.min(c.getHitPoints(), s.getCurrentHp() + gained));
        s.setSpellSlots(rebuildSlotsPreservingUsed(s.getSpellSlots(),
                SpellSlotTable.forClass(c.getCharacterClass(), c.getLevel())));
        s.setAbilities(abilitiesOf(c));
        return toDto(repository.save(s));
    }

    private static Map<String, Integer> abilitiesOf(Character c) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("STR", c.getStrength());
        m.put("DEX", c.getDexterity());
        m.put("CON", c.getConstitution());
        m.put("INT", c.getIntelligence());
        m.put("WIS", c.getWisdom());
        m.put("CHA", c.getCharisma());
        return m;
    }

    /** New slot table for the level, carrying over each level's spent count (capped at the new max). */
    private static List<SpellSlot> rebuildSlotsPreservingUsed(List<SpellSlot> current, List<SpellSlot> fresh) {
        List<SpellSlot> result = new ArrayList<>();
        for (SpellSlot f : fresh) {
            int used = 0;
            for (SpellSlot o : current) {
                if (o.level() == f.level()) {
                    used = Math.min(o.used(), f.max());
                    break;
                }
            }
            result.add(new SpellSlot(f.level(), f.max(), used));
        }
        return result;
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
            inv.set(idx, item.withQty(item.qty() - 1));
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
                inv.set(i, it.withQty(it.qty() + toAdd.qty()));
                return toDto(repository.save(s));
            }
        }
        inv.add(toAdd);
        return toDto(repository.save(s));
    }

    /**
     * Remove {@code qty} of the named item+kind stack (e.g. selling to a shop). Throws when the stack
     * is missing or holds fewer than {@code qty}, so a sale never silently under-delivers. Removes the
     * stack entirely when it hits zero.
     */
    @Transactional
    public PlayerRuntimeStateDto removeItem(UUID playerId, String itemName, ItemKind kind, int qty) {
        PlayerRuntimeState s = require(playerId);
        List<InventoryItem> inv = s.getInventory();
        int take = Math.max(1, qty);
        for (int i = 0; i < inv.size(); i++) {
            InventoryItem it = inv.get(i);
            if (it.name().equalsIgnoreCase(itemName) && it.kind() == kind) {
                if (it.qty() < take) {
                    throw new IllegalStateException("Only " + it.qty() + " " + itemName + " to give");
                }
                if (it.qty() == take) {
                    inv.remove(i);
                } else {
                    inv.set(i, it.withQty(it.qty() - take));
                }
                return toDto(repository.save(s));
            }
        }
        throw new IllegalStateException("Item not in inventory: " + itemName);
    }

    /** Add coins (in copper) to the purse — quest rewards, shop sales. Clamps at zero. Returns new state. */
    @Transactional
    public PlayerRuntimeStateDto addCoins(UUID playerId, long copperDelta) {
        PlayerRuntimeState s = require(playerId);
        s.setCopper(Math.max(0, s.getCopper() + copperDelta));
        return toDto(repository.save(s));
    }

    /** Spend coins (in copper). Throws when the purse can't cover it, so a purchase never overdraws. */
    @Transactional
    public PlayerRuntimeStateDto spendCoins(UUID playerId, long copperCost) {
        PlayerRuntimeState s = require(playerId);
        long cost = Math.max(0, copperCost);
        if (s.getCopper() < cost) {
            throw new IllegalStateException("Not enough coin: have "
                    + MoneyUtil.format(s.getCopper()) + ", need " + MoneyUtil.format(cost));
        }
        s.setCopper(s.getCopper() - cost);
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
                    inv.set(i, it.withQty(it.qty() - 1));
                }
                return toDto(repository.save(s));
            }
        }
        throw new IllegalStateException("Item not in inventory: " + itemName);
    }

    /**
     * Equip the named item into {@code targetSlot}, or unequip it back to the backpack when
     * {@code targetSlot} is {@code null}. Enforces the paper-doll rules: the slot must be one the
     * item's subtype/kind allows, and a slot holds one item at a time (equipping into an occupied
     * slot returns the previous occupant to the backpack). Display/context only — no AC change.
     */
    @Transactional
    public PlayerRuntimeStateDto equipItem(UUID playerId, String itemName, EquipSlot targetSlot) {
        PlayerRuntimeState s = require(playerId);
        List<InventoryItem> inv = s.getInventory();

        int idx = -1;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).name().equalsIgnoreCase(itemName)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalStateException("Item not in inventory: " + itemName);
        }

        InventoryItem item = inv.get(idx);

        if (targetSlot == null) {
            inv.set(idx, item.withSlot(null));
            return toDto(repository.save(s));
        }

        if (!item.allowedSlots().contains(targetSlot)) {
            throw new IllegalStateException(item.name() + " can't be equipped to the "
                    + slotLabel(targetSlot) + " slot.");
        }

        // Single occupancy: return the current occupant of this slot to the backpack first.
        for (int i = 0; i < inv.size(); i++) {
            if (i != idx && inv.get(i).slot() == targetSlot) {
                inv.set(i, inv.get(i).withSlot(null));
            }
        }

        inv.set(idx, item.withSlot(targetSlot));
        return toDto(repository.save(s));
    }

    /** Human-friendly slot name for error copy (e.g. MAIN_HAND -> "main hand"). */
    private static String slotLabel(EquipSlot slot) {
        return slot.name().toLowerCase(Locale.ROOT).replace('_', ' ');
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
     * Short rest ({@code diceToSpend} Hit Dice, at least 1 hour of in-game time — the clock is advanced
     * by the caller): spend up to the requested number of remaining Hit Dice, each healing
     * {@code roll(1d hitDie) + CON modifier} (minimum 1 per die). Does not touch spell slots, conditions,
     * or exhaustion. A dead or downed creature can't benefit. Healing is capped at the (exhaustion-aware)
     * HP maximum.
     */
    @Transactional
    public PlayerRuntimeStateDto shortRest(UUID playerId, int diceToSpend) {
        PlayerRuntimeState s = require(playerId);
        int spend = Math.max(0, Math.min(diceToSpend, s.getHitDiceRemaining()));
        if (spend > 0 && !s.isDead() && s.getCurrentHp() > 0) {
            int conMod = abilityMod(s, "CON");
            DiceRollResult roll = diceService.roll(spend + "d" + s.getHitDieSize());
            int heal = Math.max(spend, roll.total() + spend * conMod); // each die restores at least 1
            int max = ExhaustionRules.effectiveMaxHp(s.getMaxHp(), s.getExhaustionLevel());
            s.setCurrentHp(Math.min(max, s.getCurrentHp() + heal));
            s.setHitDiceRemaining(s.getHitDiceRemaining() - spend);
            log.info("Short rest: player={} spent {} Hit Dice, healed {} (now {}/{})",
                    playerId, spend, heal, s.getCurrentHp(), max);
        }
        return toDto(repository.save(s));
    }

    /**
     * Long rest ({@code nowMinutes} = the in-game clock after the rest's 8 hours): recover all spell
     * slots, restore half the character's Hit Dice (minimum one), reduce exhaustion by one level, and
     * reset the awake window so exhaustion re-accrues from now. A living (or merely dying/stable)
     * creature heals to full and resets its death-save state; a dead creature stays dead — a long rest
     * does not raise the dead.
     */
    @Transactional
    public PlayerRuntimeStateDto longRest(UUID playerId, long nowMinutes) {
        PlayerRuntimeState s = require(playerId);
        List<SpellSlot> refreshed = new ArrayList<>();
        for (SpellSlot slot : s.getSpellSlots()) {
            refreshed.add(new SpellSlot(slot.level(), slot.max(), 0));
        }
        s.setSpellSlots(refreshed);
        s.getConditions().clear();
        s.setConcentratingSpell(null);
        // A long rest reduces exhaustion by one level and starts a fresh awake window.
        s.setExhaustionLevel(Math.max(0, s.getExhaustionLevel() - 1));
        s.setExhaustionCheckMinutes(nowMinutes);
        // Regain spent Hit Dice up to half the pool (minimum one), not exceeding the total.
        int regain = Math.max(1, s.getHitDiceTotal() / 2);
        s.setHitDiceRemaining(Math.min(s.getHitDiceTotal(), s.getHitDiceRemaining() + regain));
        if (!s.isDead()) {
            s.setCurrentHp(ExhaustionRules.effectiveMaxHp(s.getMaxHp(), s.getExhaustionLevel()));
            s.setTempHp(0);
            s.setStable(false);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
        }
        return toDto(repository.save(s));
    }

    /**
     * Accrue exhaustion for every player in a session whose awake window has rolled past a full day of
     * in-game time. Each 1440 minutes since a player's {@code exhaustionCheckMinutes} adds one level
     * (capped at {@link ExhaustionRules#MAX_LEVEL}); reaching level 6 is death. Current HP is clamped to
     * the (now possibly halved) maximum. Returns the states that changed so the caller can broadcast them.
     */
    @Transactional
    public List<PlayerRuntimeStateDto> accrueExhaustion(UUID sessionId, long nowMinutes) {
        List<PlayerRuntimeStateDto> changed = new ArrayList<>();
        for (PlayerRuntimeState s : repository.findBySessionId(sessionId)) {
            if (s.isDead()) {
                continue;
            }
            boolean mutated = false;
            while (s.getExhaustionLevel() < ExhaustionRules.MAX_LEVEL
                    && nowMinutes - s.getExhaustionCheckMinutes() >= ExhaustionRules.MINUTES_PER_LEVEL) {
                s.setExhaustionLevel(s.getExhaustionLevel() + 1);
                s.setExhaustionCheckMinutes(s.getExhaustionCheckMinutes() + ExhaustionRules.MINUTES_PER_LEVEL);
                mutated = true;
            }
            if (mutated) {
                if (ExhaustionRules.isDeadly(s.getExhaustionLevel())) {
                    s.setDead(true);
                    s.setCurrentHp(0);
                } else {
                    s.setCurrentHp(Math.min(s.getCurrentHp(),
                            ExhaustionRules.effectiveMaxHp(s.getMaxHp(), s.getExhaustionLevel())));
                }
                log.info("Exhaustion: player={} now level {} (session={})",
                        s.getPlayerId(), s.getExhaustionLevel(), sessionId);
                changed.add(toDto(repository.save(s)));
            }
        }
        return changed;
    }

    /** D&D ability modifier from runtime scores: floor((score - 10) / 2); 0 when absent. */
    private static int abilityMod(PlayerRuntimeState s, String ability) {
        Integer score = s.getAbilities() == null ? null : s.getAbilities().get(ability);
        return score == null ? 0 : Math.floorDiv(score - 10, 2);
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
        // Effective AC layers three things onto the character's stored (unarmored/base) AC:
        // equipped armor + shield, then live AC-buff conditions (Mage Armor / Shield of Faith /
        // Barkskin). Both derive from the stored base so they revert automatically when gear is
        // unequipped or a condition ends — the stored armorClass column stays the base.
        Integer dex = s.getAbilities() == null ? null : s.getAbilities().get("DEX");
        int dexMod = dex == null ? 0 : Math.floorDiv(dex - 10, 2);
        int baseAc = CombatMath.armorClassBase(s.getArmorClass(), dexMod, s.getInventory());
        int effectiveAc = ConditionRules.acAdjust(s.getConditions(), baseAc, dexMod);
        // Exhaustion level 4+ halves the HP maximum; surface the effective values so the bar and
        // combat healing caps reflect it (the stored maxHp stays the base and restores when it lifts).
        int effectiveMaxHp = ExhaustionRules.effectiveMaxHp(s.getMaxHp(), s.getExhaustionLevel());
        int effectiveCurrentHp = Math.min(s.getCurrentHp(), effectiveMaxHp);
        return new PlayerRuntimeStateDto(
                s.getPlayerId(),
                effectiveCurrentHp,
                effectiveMaxHp,
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
                s.getConcentratingSpell(),
                s.getExhaustionLevel(),
                s.getHitDiceRemaining(),
                s.getHitDiceTotal(),
                s.getCopper());
    }
}
