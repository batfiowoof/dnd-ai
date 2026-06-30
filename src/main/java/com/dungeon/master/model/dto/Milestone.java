package com.dungeon.master.model.dto;

/**
 * An authored campaign milestone. When the party reaches it the DM calls the {@code awardMilestone}
 * tool with this milestone's stable {@code key}; the engine advances the whole party a level and
 * flips {@code completed} so the same beat can never fire twice. Authored upfront (preset campaigns)
 * and persisted on the session — the AI may only fire keys that exist here, never invent its own.
 */
public record Milestone(String key, String title, String description, boolean completed) {
}
