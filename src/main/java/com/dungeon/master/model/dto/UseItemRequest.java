package com.dungeon.master.model.dto;

/** WebSocket inbound: consume an inventory item by name. */
public record UseItemRequest(
        String itemName
) {
}
