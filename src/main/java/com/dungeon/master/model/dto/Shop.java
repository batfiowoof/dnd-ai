package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ShopType;

import java.util.List;

/**
 * A merchant the party can trade with — authored in the World Builder, compiled onto a
 * {@link com.dungeon.master.model.entity.GameSession}, and only usable while the party is standing at
 * its location. A shop is anchored to a {@code region} and (optionally) a {@code subregion}; it is
 * "open" only when the session's current region/subregion match (resolved by name at runtime, the same
 * convention as {@code WorldNpc}). Leaving town — which clears the session's current subregion — closes it.
 *
 * <p>The {@code economyFactor} makes the same goods cost different amounts in different cities: the buy
 * price of each stock line is {@code round(basePriceCopper × economyFactor)} and the sell (buy-back)
 * price is half of that. One factor per shop, applied uniformly to every item.
 *
 * <p>{@code key} is engine-owned — the sanitizer backfills a stable kebab id and clamps the factor.
 *
 * @param key           stable kebab-case id, unique across the world's shops
 * @param name          display name (e.g. "The Rusty Anvil")
 * @param type          broad flavour, used for labels and AI stock generation
 * @param description   a sentence or two of flavour
 * @param region        the {@code WorldRegion} name this shop sits in
 * @param subregion     the {@code WorldSubregion} within {@code region}, or null for a region-level shop
 * @param economyFactor price multiplier (clamped ~0.5–2.0); &gt;1 = pricey, &lt;1 = cheap
 * @param ownerNpcName  optional name of the owning NPC, for flavour
 * @param stock         the goods on offer
 */
public record Shop(
        String key,
        String name,
        ShopType type,
        String description,
        String region,
        String subregion,
        double economyFactor,
        String ownerNpcName,
        List<ShopStockEntry> stock
) {
}
