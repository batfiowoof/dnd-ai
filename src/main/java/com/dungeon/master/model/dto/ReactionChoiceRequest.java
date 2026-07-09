package com.dungeon.master.model.dto;

/**
 * WebSocket inbound: a player's answer to a reaction prompt. {@code choice} is one of
 * SHIELD / ABSORB / DECLINE; {@code promptId} correlates it to the paused attack.
 */
public record ReactionChoiceRequest(String choice, java.util.UUID promptId) {
}
