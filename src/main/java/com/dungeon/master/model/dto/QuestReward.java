package com.dungeon.master.model.dto;

import java.util.List;

/**
 * What a quest pays out when it completes. {@code items} are granted to the whole party through
 * {@code PlayerStateService.addItem} — coin is just an inventory item (e.g. a {@code GEAR} "150 GP"),
 * reusing the existing inventory system rather than a separate currency. {@code milestoneKey}, when set,
 * links the quest to an authored {@link Milestone}: completing the quest fires the milestone (levelling
 * the whole party). {@code description} is free-text flavour for the DM and the log.
 */
public record QuestReward(String description, List<InventoryItem> items, String milestoneKey) {
}
