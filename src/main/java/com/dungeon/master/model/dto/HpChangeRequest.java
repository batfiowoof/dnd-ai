package com.dungeon.master.model.dto;

/** WebSocket inbound: apply a manual HP delta. Positive heals, negative damages. */
public record HpChangeRequest(
        int amount
) {
}
