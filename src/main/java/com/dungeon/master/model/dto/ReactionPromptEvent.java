package com.dungeon.master.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Sent privately to a single player (over {@code /user/queue/reaction}) when an enemy attack has hit
 * them and they could spend their reaction on a spell. The engine is paused until the player answers
 * with a {@code /combat/reaction} choice or the {@code secondsLeft} window elapses (auto-decline).
 *
 * @param type        discriminator, always {@link #TYPE}
 * @param sessionId   the session this prompt belongs to
 * @param promptId    correlates the player's answer back to the paused attack
 * @param attacker    display name of the attacking enemy
 * @param damageType  the triggering damage type (drives which spells are offered), or null
 * @param spellOptions the reaction spells the player may cast now ("SHIELD" / "ABSORB")
 * @param secondsLeft the decision window in seconds before an automatic decline
 */
public record ReactionPromptEvent(
        String type,
        UUID sessionId,
        UUID promptId,
        String attacker,
        String damageType,
        List<String> spellOptions,
        int secondsLeft
) {
    public static final String TYPE = "REACTION_PROMPT";

    public static ReactionPromptEvent of(UUID sessionId, UUID promptId, String attacker,
                                         String damageType, List<String> spellOptions, int secondsLeft) {
        return new ReactionPromptEvent(TYPE, sessionId, promptId, attacker, damageType,
                spellOptions, secondsLeft);
    }
}
