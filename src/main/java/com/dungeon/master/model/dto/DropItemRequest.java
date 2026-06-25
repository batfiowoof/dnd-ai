package com.dungeon.master.model.dto;

/** Inbound WebSocket payload for dropping one of a named inventory item. */
public record DropItemRequest(
        String name
) {
}
