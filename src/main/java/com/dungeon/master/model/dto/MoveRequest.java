package com.dungeon.master.model.dto;

/**
 * WebSocket inbound: the active player's tactical move to grid square (x, y).
 * The backend validates reachability/cost and resolves opportunity attacks.
 */
public record MoveRequest(int x, int y) {
}
