package com.dungeon.master.model.dto;

/**
 * Buy request over STOMP. {@code itemRef} is the stock line's SRD index or display name; {@code qty}
 * is how many to buy (min 1).
 */
public record ShopBuyRequest(String shopKey, String itemRef, int qty) {
}
