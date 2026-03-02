package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;

public record PlayerActionRequest(
        @NotBlank(message = "Action is required") String action
) {
}
