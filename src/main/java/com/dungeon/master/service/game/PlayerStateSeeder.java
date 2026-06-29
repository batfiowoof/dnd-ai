package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;
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

/**
 * Seeds a player's per-session {@link PlayerRuntimeState} row from their {@code Character} template
 * (or neutral defaults when none is linked). Kept separate from {@link PlayerStateService}, which
 * owns the in-game mutations, so the one-time seeding concern lives on its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerStateSeeder {

    private final PlayerRuntimeStateRepository repository;

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
}
