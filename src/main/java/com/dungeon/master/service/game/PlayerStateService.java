package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
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
        PlayerRuntimeState s = require(playerId);
        if (amount >= 0) {
            heal(s, amount);
        } else {
            damage(s, -amount);
        }
        return toDto(repository.save(s));
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

    /** Long rest: recover all spell slots, heal to max HP, and clear conditions. */
    @Transactional
    public PlayerRuntimeStateDto longRest(UUID playerId) {
        PlayerRuntimeState s = require(playerId);
        List<SpellSlot> refreshed = new ArrayList<>();
        for (SpellSlot slot : s.getSpellSlots()) {
            refreshed.add(new SpellSlot(slot.level(), slot.max(), 0));
        }
        s.setSpellSlots(refreshed);
        s.setCurrentHp(s.getMaxHp());
        s.setTempHp(0);
        s.getConditions().clear();
        return toDto(repository.save(s));
    }

    /* ── internals ───────────────────────────────────────────────── */

    /** Heal up to max HP; returns HP actually restored. */
    private int heal(PlayerRuntimeState s, int amount) {
        int before = s.getCurrentHp();
        s.setCurrentHp(Math.min(s.getMaxHp(), before + Math.max(0, amount)));
        return s.getCurrentHp() - before;
    }

    /** Apply damage, absorbing temp HP first; HP floors at 0. */
    private void damage(PlayerRuntimeState s, int amount) {
        int dmg = Math.max(0, amount);
        int absorbed = Math.min(s.getTempHp(), dmg);
        s.setTempHp(s.getTempHp() - absorbed);
        int remaining = dmg - absorbed;
        s.setCurrentHp(Math.max(0, s.getCurrentHp() - remaining));
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
                s.getConditions(),
                s.getCantrips(),
                s.getKnownSpells(),
                s.isInspiration());
    }
}
