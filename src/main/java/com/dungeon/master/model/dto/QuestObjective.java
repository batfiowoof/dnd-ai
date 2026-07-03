package com.dungeon.master.model.dto;

/**
 * One ordered stage of a quest. The DM ticks these off one at a time via the {@code advanceQuest} tool
 * as the party clears them; {@code completed} is engine-owned (the sanitizer forces it {@code false} on
 * the authored template) and {@code key} is a stable id the tool names.
 */
public record QuestObjective(String key, String description, boolean completed) {
}
