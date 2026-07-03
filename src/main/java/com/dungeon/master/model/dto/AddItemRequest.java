package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.ItemSubtype;

/** Inbound WebSocket payload for adding (or stacking) an item into a player's inventory. */
public record AddItemRequest(
        String name,
        int qty,
        ItemKind kind,
        ItemSubtype subtype
) {
}
