package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.MagicItemEffect;
import com.dungeon.master.model.enums.MagicItemRarity;
import com.dungeon.master.service.ai.SrdContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three ways {@link MagicItemCatalog} resolves an item's mechanics: {@code +N} name
 * synthesis, typed-Resistance name synthesis, and prose-header parsing merged with the curated
 * effect overlay. The header/overlay cases load the real bundled SRD resources.
 */
class MagicItemCatalogTest {

    /** A synthesis-only catalog (SrdContent unloaded → empty byName; forItemName still synthesizes). */
    private static MagicItemCatalog synthesisCatalog() {
        return new MagicItemCatalog(new SrdContent());
    }

    /** A catalog with the real SRD corpus + overlay loaded (both @PostConstruct loaders invoked). */
    private static MagicItemCatalog loadedCatalog() {
        SrdContent srd = new SrdContent();
        ReflectionTestUtils.invokeMethod(srd, "load");
        MagicItemCatalog catalog = new MagicItemCatalog(srd);
        ReflectionTestUtils.invokeMethod(catalog, "load");
        return catalog;
    }

    @Test
    void synthesizesPlusNWeapon() {
        MagicItemEffect e = synthesisCatalog().forItemName("+2 Longsword").orElseThrow();
        assertEquals(2, e.attackBonus());
        assertEquals(2, e.damageBonus());
        assertEquals(0, e.acBonus());
        assertFalse(e.requiresAttunement());
    }

    @Test
    void synthesizesPlusNArmor() {
        MagicItemEffect e = synthesisCatalog().forItemName("+1 Plate Armor").orElseThrow();
        assertEquals(1, e.acBonus());
        assertEquals(0, e.attackBonus());
    }

    @Test
    void synthesizesTypedResistance() {
        MagicItemEffect e = synthesisCatalog().forItemName("Ring of Fire Resistance").orElseThrow();
        assertTrue(e.resistances().contains("Fire"));
        assertTrue(e.requiresAttunement());
    }

    @Test
    void mundaneItemIsNotMagic() {
        assertTrue(synthesisCatalog().forItemName("Torch").isEmpty());
    }

    @Test
    void parsesHeaderAndMergesOverlay() {
        MagicItemCatalog catalog = loadedCatalog();
        assertFalse(catalog.isEmpty(), "SRD magic items should have loaded");

        MagicItemEffect ring = catalog.forItemName("Ring of Protection").orElseThrow();
        assertTrue(ring.requiresAttunement());
        assertEquals(MagicItemRarity.RARE, ring.rarity());
        assertEquals(1, ring.acBonus());
        assertEquals(1, ring.saveBonus());

        MagicItemEffect gauntlets = catalog.forItemName("Gauntlets of Ogre Power").orElseThrow();
        assertEquals(19, gauntlets.setAbility().get("STR"));
        assertTrue(gauntlets.requiresAttunement());
    }
}
