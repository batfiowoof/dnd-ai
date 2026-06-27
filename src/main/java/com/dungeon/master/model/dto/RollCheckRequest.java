package com.dungeon.master.model.dto;

/**
 * WebSocket inbound: a player resolves the DM-requested ability check pending for them. The
 * free normal/advantage/disadvantage picker is gone — the player's only roll-mode lever is
 * whether to spend Inspiration (granting advantage). The backend computes the modifier, DC,
 * and the combined roll mode (DM situational mode RAW-combined with spent inspiration).
 */
public record RollCheckRequest(
        boolean spendInspiration
) {
}
