package com.dungeon.master.model.dto;

/**
 * WebSocket inbound: the active player toggles whether they are holding their reaction for a spell
 * (Shield / Absorb Elements) instead of auto-taking opportunity attacks.
 */
public record HoldReactionRequest(boolean hold) {
}
