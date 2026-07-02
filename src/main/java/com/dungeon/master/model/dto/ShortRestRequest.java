package com.dungeon.master.model.dto;

/** WebSocket inbound: a player takes a short rest, spending {@code hitDice} Hit Dice to heal. */
public record ShortRestRequest(
        int hitDice
) {
}
