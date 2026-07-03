package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.EquipSlot;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.ItemSubtype;
import com.dungeon.master.service.game.combat.CombatMath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Equip-slot derivation, the withSlot/withQty copies, and off-hand weapon inference. */
class InventoryItemTest {

    @Test
    void allowedSlotsFromSubtypeThenKind() {
        // Explicit subtype wins.
        InventoryItem helm = new InventoryItem("Iron Helm", 1, ItemKind.ARMOR, false, null, ItemSubtype.HELMET);
        assertTrue(helm.allowedSlots().contains(EquipSlot.HEAD));
        assertFalse(helm.allowedSlots().contains(EquipSlot.CHEST));

        // No subtype → derived from kind (WEAPON → both hands, ARMOR → chest).
        InventoryItem sword = new InventoryItem("Longsword", 1, ItemKind.WEAPON);
        assertTrue(sword.allowedSlots().contains(EquipSlot.MAIN_HAND));
        assertTrue(sword.allowedSlots().contains(EquipSlot.OFF_HAND));

        InventoryItem mail = new InventoryItem("Chain Mail", 1, ItemKind.ARMOR);
        assertTrue(mail.allowedSlots().contains(EquipSlot.CHEST));

        // Non-equippable gear maps to no slots.
        assertTrue(new InventoryItem("Rope", 1, ItemKind.GEAR).allowedSlots().isEmpty());
    }

    @Test
    void withSlotKeepsEquippedConsistentAndWithQtyPreservesFields() {
        InventoryItem dagger = new InventoryItem("Dagger", 1, ItemKind.WEAPON);
        InventoryItem equipped = dagger.withSlot(EquipSlot.OFF_HAND);
        assertEquals(EquipSlot.OFF_HAND, equipped.slot());
        assertTrue(equipped.equipped());

        InventoryItem unequipped = equipped.withSlot(null);
        assertNull(unequipped.slot());
        assertFalse(unequipped.equipped());

        // withQty must not drop slot/subtype/equipped.
        InventoryItem stacked = equipped.withQty(3);
        assertEquals(3, stacked.qty());
        assertEquals(EquipSlot.OFF_HAND, stacked.slot());
        assertTrue(stacked.equipped());
    }

    @Test
    void armorClassBaseFromEquippedArmorAndShield() {
        int dexMod = 3; // high DEX to exercise the medium/heavy caps
        int fallback = 15;

        // No armor equipped → keep the character's stored AC.
        assertEquals(15, CombatMath.armorClassBase(fallback, dexMod, List.of()));

        // Light armor (studded leather 12) adds the full DEX mod.
        InventoryItem studded = new InventoryItem("Studded Leather", 1, ItemKind.ARMOR)
                .withSlot(EquipSlot.CHEST);
        assertEquals(15, CombatMath.armorClassBase(fallback, dexMod, List.of(studded))); // 12 + 3

        // Medium armor (breastplate 14) caps DEX at +2.
        InventoryItem breastplate = new InventoryItem("Breastplate", 1, ItemKind.ARMOR)
                .withSlot(EquipSlot.CHEST);
        assertEquals(16, CombatMath.armorClassBase(fallback, dexMod, List.of(breastplate))); // 14 + 2

        // Heavy armor (plate 18) ignores DEX.
        InventoryItem plate = new InventoryItem("Plate Armor", 1, ItemKind.ARMOR)
                .withSlot(EquipSlot.CHEST);
        assertEquals(18, CombatMath.armorClassBase(fallback, dexMod, List.of(plate))); // 18 + 0

        // A shield (subtype SHIELD in OFF_HAND) adds +2 on top.
        InventoryItem shield = new InventoryItem("Shield", 1, ItemKind.ARMOR, false, null, ItemSubtype.SHIELD)
                .withSlot(EquipSlot.OFF_HAND);
        assertEquals(20, CombatMath.armorClassBase(fallback, dexMod, List.of(plate, shield))); // 18 + 2
        // Shield alone (no body armor) stacks on the fallback AC.
        assertEquals(17, CombatMath.armorClassBase(fallback, dexMod, List.of(shield))); // 15 + 2
    }

    @Test
    void offHandWeaponAndDamageDropAbilityModifier() {
        InventoryItem mainSword = new InventoryItem("Longsword", 1, ItemKind.WEAPON).withSlot(EquipSlot.MAIN_HAND);
        InventoryItem offDagger = new InventoryItem("Dagger", 1, ItemKind.WEAPON).withSlot(EquipSlot.OFF_HAND);
        List<InventoryItem> inv = List.of(mainSword, offDagger);

        InventoryItem off = CombatMath.offHandWeapon(inv);
        assertEquals("Dagger", off.name());
        // Off-hand damage is the bare weapon die — no "+mod".
        assertEquals("1d4", CombatMath.offHandDamageDice(off));

        // No off-hand weapon equipped → null (the service rejects the action).
        assertNull(CombatMath.offHandWeapon(List.of(mainSword)));
    }
}
