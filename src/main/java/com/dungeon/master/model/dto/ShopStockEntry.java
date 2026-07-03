package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ItemKind;

/**
 * One line of a {@link Shop}'s inventory. The {@code basePriceCopper} is the item's <em>list</em>
 * price in copper (from the SRD catalog at authoring time, or hand-set); the shop's
 * {@code economyFactor} scales it into the actual buy price. {@code quantity} is the number in stock,
 * or {@code -1} for an unlimited supply; on a running session's copy it decrements as the party buys.
 *
 * @param srdIndex        the SRD equipment index this came from (e.g. "longsword"), or null for a custom item
 * @param name            display name (e.g. "Longsword")
 * @param kind            item category, so purchases stack correctly in inventory
 * @param basePriceCopper list price in copper before the economy factor
 * @param quantity        units in stock, or -1 for unlimited
 */
public record ShopStockEntry(
        String srdIndex,
        String name,
        ItemKind kind,
        long basePriceCopper,
        int quantity
) {
}
