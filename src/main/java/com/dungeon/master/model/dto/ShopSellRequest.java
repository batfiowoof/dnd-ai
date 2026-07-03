package com.dungeon.master.model.dto;

/** Sell request over STOMP: sell {@code qty} (min 1) of the named item the player holds to the shop. */
public record ShopSellRequest(String shopKey, String name, int qty) {
}
