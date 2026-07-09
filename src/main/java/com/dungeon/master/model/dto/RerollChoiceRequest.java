package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * WebSocket inbound: a player's answer to a reroll prompt. {@code resource} is one of
 * INSPIRATION / LUCK / KEEP; {@code promptId} correlates it to the paused roll.
 */
public record RerollChoiceRequest(String resource, UUID promptId) {
}
