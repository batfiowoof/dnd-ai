package com.dungeon.master.model.dto;

import java.util.List;

/**
 * What a player sees when they open the shop panel: their current coin purse (copper) and the shops
 * open at the party's current location. Empty {@code shops} means there's nowhere to trade right now.
 */
public record AvailableShopsDto(long copper, List<ShopView> shops) {
}
