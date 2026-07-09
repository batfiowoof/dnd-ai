package com.dungeon.master.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Sent privately to a single player (over {@code /user/queue/reroll}) right after one of their d20
 * rolls <em>fails</em>, when they hold a resource that can reroll it (Heroic Inspiration and/or Lucky
 * points). The DM's roll is paused until the player answers with a {@code /roll/reroll} choice or the
 * {@code secondsLeft} window elapses (auto-keep). Mirrors {@link ReactionPromptEvent}.
 *
 * @param type         discriminator, always {@link #TYPE}
 * @param sessionId    the session this prompt belongs to
 * @param promptId     correlates the player's answer back to the paused roll
 * @param label        the roll's display label (e.g. "DEX (Stealth) check")
 * @param originalTotal the failed roll's total
 * @param dc           the DC the roll was measured against
 * @param options      spendable resources offered, from {@link com.dungeon.master.model.enums.RerollResource}
 *                     ("INSPIRATION" / "LUCK")
 * @param luckPoints   the player's remaining Lucky points (for display), 0 when they have none
 * @param secondsLeft  the decision window in seconds before an automatic keep
 */
public record RerollPromptEvent(
        String type,
        UUID sessionId,
        UUID promptId,
        String label,
        int originalTotal,
        int dc,
        List<String> options,
        int luckPoints,
        int secondsLeft
) {
    public static final String TYPE = "REROLL_PROMPT";

    public static RerollPromptEvent of(UUID sessionId, UUID promptId, String label, int originalTotal,
                                       int dc, List<String> options, int luckPoints, int secondsLeft) {
        return new RerollPromptEvent(TYPE, sessionId, promptId, label, originalTotal, dc, options,
                luckPoints, secondsLeft);
    }
}
