package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.enums.ItemKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 5e coin parsing, coin-name detection, and gp/sp/cp formatting. */
class MoneyUtilTest {

    @Test
    void parsesGpSpCpToCopper() {
        assertEquals(15000, MoneyUtil.parseCoins("150 GP"));
        assertEquals(50, MoneyUtil.parseCoins("5 sp"));
        assertEquals(1, MoneyUtil.parseCoins("1 CP"));
        assertEquals(100000, MoneyUtil.parseCoins("1,000 gp"));
        assertEquals(-1, MoneyUtil.parseCoins("a healing potion"));
        assertEquals(-1, MoneyUtil.parseCoins(null));
    }

    @Test
    void detectsCoinOnlyNames() {
        assertTrue(MoneyUtil.isCoinName("150 GP"));
        assertTrue(MoneyUtil.isCoinName("5 sp"));
        assertFalse(MoneyUtil.isCoinName("Longsword"));
        assertFalse(MoneyUtil.isCoinName("150 GP pouch")); // has extra words → a real item
    }

    @Test
    void coinValueOfMultipliesByQty() {
        assertEquals(30000, MoneyUtil.coinValueOf(new InventoryItem("150 GP", 2, ItemKind.GEAR)));
        assertEquals(-1, MoneyUtil.coinValueOf(new InventoryItem("Longsword", 1, ItemKind.WEAPON)));
    }

    @Test
    void formatsCopperDroppingZeroDenominations() {
        assertEquals("12 gp 4 sp 2 cp", MoneyUtil.format(12_42));
        assertEquals("15 gp", MoneyUtil.format(1500));
        assertEquals("5 sp", MoneyUtil.format(50));
        assertEquals("2 cp", MoneyUtil.format(2));
        assertEquals("0 cp", MoneyUtil.format(0));
        assertEquals("0 cp", MoneyUtil.format(-100)); // clamped
    }
}
