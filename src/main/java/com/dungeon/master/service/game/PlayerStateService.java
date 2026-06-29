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

    /* ── seeding ─────────────────────────────────────────────────── */

    /**
     * Ensure a runtime state row exists for the player, seeding it if missing. Combat reads
     * runtime HP to decide who is up; a player whose state was never seeded (or whose seeding
     * failed) would otherwise be treated as "down" and silently skipped — an instant TPK.
     * Seeds from the {@code Character} template when available, else neutral defaults.
     */
    @Transactional
    public void ensureSeeded(Player player, Character character) {
        if (repository.existsById(player.getId())) {
            return;
        }
        if (character != null) {
            seedForPlayer(player, character);
        } else {
            seedDefaultsForPlayer(player);
        }
    }

    /**
     * Seed neutral default runtime state for a player with no linked character (HP 10/10,
     * AC 10, all abilities 10, no spell slots, a single stack of healing potions). Keeps
     * combat and checks functional rather than crashing on a null character template.
     */
    @Transactional
    public void seedDefaultsForPlayer(Player player) {
        Map<String, Integer> abilities = new LinkedHashMap<>();
        for (String a : List.of("STR", "DEX", "CON", "INT", "WIS", "CHA")) {
            abilities.put(a, 10);
        }
        List<InventoryItem> inventory = new ArrayList<>();
        inventory.add(new InventoryItem("Potion of Healing", 2, ItemKind.POTION_HEALING));

        PlayerRuntimeState state = PlayerRuntimeState.builder()
                .playerId(player.getId())
                .sessionId(player.getSessionId())
                .currentHp(10)
                .maxHp(10)
                .tempHp(0)
                .armorClass(10)
                .abilities(abilities)
                .spellSlots(new ArrayList<>())
                .inventory(inventory)
                .conditions(new ArrayList<>())
                .cantrips(new ArrayList<>())
                .knownSpells(new ArrayList<>())
                .build();
        repository.save(state);
        log.info("Seeded default runtime state for player={} (no character)", player.getId());
    }

    @Transactional
    public void seedForPlayer(Player player, Character character) {
        int hp = character.getHitPoints();

        List<InventoryItem> inventory = new ArrayList<>();
        // Prefer the structured starting inventory (real quantities + kinds); fall back to
        // the legacy equipment string list with best-effort classification.
        if (character.getStartingInventory() != null && !character.getStartingInventory().isEmpty()) {
            for (InventoryItem item : character.getStartingInventory()) {
                inventory.add(new InventoryItem(item.name(), Math.max(1, item.qty()), item.kind(), item.equipped()));
            }
        } else if (character.getEquipment() != null) {
            for (String name : character.getEquipment()) {
                inventory.add(new InventoryItem(name, 1, classify(name)));
            }
        }
        inventory.add(new InventoryItem("Potion of Healing", 2, ItemKind.POTION_HEALING));

        Map<String, Integer> abilities = new LinkedHashMap<>();
        abilities.put("STR", character.getStrength());
        abilities.put("DEX", character.getDexterity());
        abilities.put("CON", character.getConstitution());
        abilities.put("INT", character.getIntelligence());
        abilities.put("WIS", character.getWisdom());
        abilities.put("CHA", character.getCharisma());

        PlayerRuntimeState state = PlayerRuntimeState.builder()
                .playerId(player.getId())
                .sessionId(player.getSessionId())
                .currentHp(hp)
                .maxHp(hp)
                .tempHp(0)
                .armorClass(character.getArmorClass())
                .abilities(abilities)
                .spellSlots(SpellSlotTable.forClass(character.getCharacterClass(), character.getLevel()))
                .inventory(inventory)
                .conditions(new ArrayList<>())
                .cantrips(character.getCantrips() != null ? new ArrayList<>(character.getCantrips()) : new ArrayList<>())
                .knownSpells(character.getKnownSpells() != null ? new ArrayList<>(character.getKnownSpells()) : new ArrayList<>())
                .build();
        repository.save(state);
        log.info("Seeded runtime state for player={} hp={}", player.getId(), hp);
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
            heal(s, amount);
        } else {
            damage(s, -amount, critical);
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
        DeathSaveOutcome outcome;
        if (roll.crit()) {                                   // natural 20 → back on your feet at 1 HP
            s.setCurrentHp(Math.min(1, s.getMaxHp()));
            s.setStable(false);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
            outcome = DeathSaveOutcome.REVIVED;
        } else if (roll.fumble()) {                          // natural 1 → two failures
            outcome = addFailures(s, 2);
        } else if (roll.total() >= 10) {                     // 10+ → a success
            s.setDeathSaveSuccesses(Math.min(3, s.getDeathSaveSuccesses() + 1));
            if (s.getDeathSaveSuccesses() >= 3) {
                s.setStable(true);                           // stable: still 0 HP, stops rolling
                outcome = DeathSaveOutcome.STABILIZED;
            } else {
                outcome = DeathSaveOutcome.SUCCESS;
            }
        } else {                                             // 9 or less → a failure
            outcome = addFailures(s, 1);
        }
        return new DeathSaveResult(toDto(repository.save(s)), outcome);
    }

    private DeathSaveOutcome addFailures(PlayerRuntimeState s, int n) {
        s.setDeathSaveFailures(Math.min(3, s.getDeathSaveFailures() + n));
        if (s.getDeathSaveFailures() >= 3) {
            s.setDead(true);
            return DeathSaveOutcome.DIED;
        }
        return DeathSaveOutcome.FAILURE;
    }

    /**
     * Stabilize a dying creature (e.g. a successful DC 10 Medicine check): it stays at 0 HP and
     * unconscious but stops rolling death saves. Idempotent when already stable; rejects targets
     * that aren't dying.
     */
    @Transactional
    public PlayerRuntimeStateDto stabilize(UUID playerId) {
        PlayerRuntimeState s = require(playerId);
        if (s.isDead()) {
            throw new IllegalStateException("Cannot stabilize a dead creature");
        }
        if (s.getCurrentHp() > 0) {
            throw new IllegalStateException("Target is conscious and does not need stabilizing");
        }
        if (!s.isStable()) {
            s.setStable(true);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
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
            healed = heal(s, healRoll.total());
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

    /**
     * Heal up to max HP; returns HP actually restored. Any positive heal to a dying/stable creature
     * (0 HP, not dead) revives it — clearing {@code stable} and resetting both death-save counters.
     * You cannot heal the dead: a {@code dead} creature is a no-op.
     */
    private int heal(PlayerRuntimeState s, int amount) {
        if (s.isDead()) {
            return 0;                                        // cannot heal the dead
        }
        int amt = Math.max(0, amount);
        int before = s.getCurrentHp();
        if (before == 0 && amt > 0) {                        // revive a downed creature
            s.setStable(false);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
        }
        s.setCurrentHp(Math.min(s.getMaxHp(), before + amt));
        return s.getCurrentHp() - before;
    }

    /**
     * Apply damage, absorbing temp HP first; HP floors at 0. Implements 5e dying rules:
     * <ul>
     *   <li>A no-op once the creature is {@code dead}.</li>
     *   <li>Dropping a conscious creature to 0 HP makes it <em>dying</em> (resets death saves,
     *       clears stable); if the leftover damage past 0 is ≥ max HP it is <em>massive damage</em>
     *       → instant death.</li>
     *   <li>Damage to a creature already at 0 HP inflicts a death-save failure (two on a critical),
     *       and a stable creature reverts to dying. Damage ≥ max HP is instant death (the crit only
     *       affects the failure count, not the instant-death threshold). Three failures → dead.</li>
     * </ul>
     */
    private void damage(PlayerRuntimeState s, int amount, boolean critical) {
        if (s.isDead()) {
            return;                                          // already dead — nothing to do
        }
        int dmg = Math.max(0, amount);
        int absorbed = Math.min(s.getTempHp(), dmg);
        s.setTempHp(s.getTempHp() - absorbed);
        int remaining = dmg - absorbed;
        if (remaining <= 0) {
            return;                                          // fully soaked by temp HP
        }

        if (s.getCurrentHp() > 0) {
            int hpBefore = s.getCurrentHp();
            int leftover = remaining - hpBefore;             // damage past 0
            s.setCurrentHp(Math.max(0, hpBefore - remaining));
            if (s.getCurrentHp() == 0) {                     // dropped to 0 → dying
                s.setDeathSaveSuccesses(0);
                s.setDeathSaveFailures(0);
                s.setStable(false);
                if (leftover >= s.getMaxHp()) {              // massive damage → instant death
                    s.setDead(true);
                }
            }
        } else {                                             // already at 0 HP (dying or stable)
            boolean wasStable = s.isStable();
            s.setStable(false);                              // a stable creature reverts to dying
            if (remaining >= s.getMaxHp()) {                 // any damage ≥ max HP → instant death
                s.setDead(true);
                return;
            }
            if (wasStable) {                                 // re-downed: start a fresh dying state
                s.setDeathSaveSuccesses(0);
                s.setDeathSaveFailures(0);
            }
            s.setDeathSaveFailures(Math.min(3, s.getDeathSaveFailures() + (critical ? 2 : 1)));
            if (s.getDeathSaveFailures() >= 3) {
                s.setDead(true);
            }
        }
    }

    private PlayerRuntimeState require(UUID playerId) {
        return repository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "No runtime state for player: " + playerId));
    }

    private ItemKind classify(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("potion")) return ItemKind.POTION;
        if (n.contains("scroll")) return ItemKind.SCROLL;
        if (n.contains("armor") || n.contains("mail") || n.contains("leather")
                || n.contains("shield") || n.contains("plate")) return ItemKind.ARMOR;
        if (n.contains("sword") || n.contains("axe") || n.contains("bow")
                || n.contains("dagger") || n.contains("mace") || n.contains("staff")
                || n.contains("club") || n.contains("spear") || n.contains("hammer")
                || n.contains("crossbow") || n.contains("rapier") || n.contains("javelin")
                || n.contains("warhammer") || n.contains("quarterstaff")) return ItemKind.WEAPON;
        return ItemKind.GEAR;
    }

    private PlayerRuntimeStateDto toDto(PlayerRuntimeState s) {
        return new PlayerRuntimeStateDto(
                s.getPlayerId(),
                s.getCurrentHp(),
                s.getMaxHp(),
                s.getTempHp(),
                s.getArmorClass(),
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
