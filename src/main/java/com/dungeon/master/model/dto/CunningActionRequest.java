package com.dungeon.master.model.dto;

/** Inbound WebSocket payload selecting which Cunning Action (dash / disengage / hide) to take. */
public record CunningActionRequest(
        String action
) {
}
