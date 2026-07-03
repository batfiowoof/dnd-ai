package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.ShopType;

import java.util.List;

/**
 * A shop as presented to a specific player at the current location: buy prices are already
 * economy-adjusted, and {@code sellOffers} are that player's own items this shop will buy back, priced.
 * The frontend renders this directly — it never re-derives prices.
 */
public record ShopView(
        String key,
        String name,
        ShopType type,
        String description,
        String region,
        String subregion,
        double economyFactor,
        String ownerNpcName,
        List<Stock> stock,
        List<SellOffer> sellOffers
) {
    /** One item for sale, with its economy-adjusted unit buy price. {@code quantity} -1 = unlimited. */
    public record Stock(String srdIndex, String name, ItemKind kind, long unitPriceCopper, int quantity) {
    }

    /** One of the player's items this shop will buy back, with the unit price offered and how many they hold. */
    public record SellOffer(String name, ItemKind kind, int held, long unitPriceCopper) {
    }
}
