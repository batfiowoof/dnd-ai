package com.dungeon.master.model.dto;

/**
 * AI-generated draft of a world's Overview step: a one-line hook, tone/magic tags, and the campaign
 * one-pager. Mapped directly from the chat model's JSON response.
 */
public record WorldOverviewSuggestion(
        String tagline,
        String tone,
        String magicLevel,
        String overview
) {
}
