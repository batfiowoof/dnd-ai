package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.EquipSlot;
import com.dungeon.master.model.enums.MagicItemRarity;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable mechanics for one magic item. Metadata ({@code itemType}, {@code slot},
 * {@code rarity}, {@code requiresAttunement}) is parsed from the SRD prose <em>header</em> by
 * {@code MagicItemCatalog} for all 251 items; the effect fields come from a curated overlay
 * ({@code resources/dnd5e/magic-item-effects.json}) or are synthesized from a {@code +N} item
 * name. Uncurated items carry {@code parsed == false} and are DM-narrated only. Modeled on the
 * flat-record style of {@link SpellEffect}.
 *
 * @param key                stable slug ({@code SRD:MAGIC_ITEM:<slug>} → the {@code <slug>} part)
 * @param name               item display name
 * @param itemType           prose item type, e.g. "Wondrous Item", "Armor", "Weapon", "Ring"
 * @param slot               best-effort paper-doll slot the item occupies, or null (carried/none)
 * @param rarity             rarity tier from the header
 * @param requiresAttunement whether the header says "Requires Attunement"
 * @param acBonus            flat bonus to Armor Class while the effect is live
 * @param attackBonus        flat bonus to weapon attack rolls (a magic weapon's +N)
 * @param damageBonus        flat bonus to weapon damage rolls (a magic weapon's +N)
 * @param saveBonus          flat bonus to all saving throws (Ring/Cloak of Protection)
 * @param requiresNoArmor    the AC bonus only applies when no body armor/shield is worn (Bracers of Defense)
 * @param resistances        damage types this item grants Resistance to (halved incoming damage)
 * @param setAbility         ability scores this item sets to a fixed value (e.g. {@code {STR:19}}); the higher of base vs set wins
 * @param advantageOn        tags this item grants advantage on (e.g. "save", "STR-save") — best-effort surfacing
 * @param parsed             true when the engine resolves it mechanically (else narrate only)
 */
public record MagicItemEffect(
        String key,
        String name,
        String itemType,
        EquipSlot slot,
        MagicItemRarity rarity,
        boolean requiresAttunement,
        int acBonus,
        int attackBonus,
        int damageBonus,
        int saveBonus,
        boolean requiresNoArmor,
        List<String> resistances,
        Map<String, Integer> setAbility,
        List<String> advantageOn,
        boolean parsed
) {
    /** True when this item carries any mechanical effect the engine can resolve. */
    public boolean hasMechanicalEffect() {
        return acBonus != 0 || attackBonus != 0 || damageBonus != 0 || saveBonus != 0
                || (resistances != null && !resistances.isEmpty())
                || (setAbility != null && !setAbility.isEmpty())
                || (advantageOn != null && !advantageOn.isEmpty());
    }
}
