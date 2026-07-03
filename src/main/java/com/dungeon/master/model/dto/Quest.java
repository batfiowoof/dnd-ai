package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.QuestStatus;
import com.dungeon.master.model.enums.QuestType;

import java.util.List;

/**
 * An authored quest — a richer sibling of {@link Milestone}. Authored in the World Builder, compiled onto
 * a {@link com.dungeon.master.model.entity.GameSession}, surfaced to the DM through the session directive,
 * and driven mid-play by the {@code DmQuestTools} (start / advance / complete / fail) — the AI may only
 * name keys that exist here, never invent its own.
 *
 * <p>Quests chain via {@code prerequisiteKeys} (a quest is {@code LOCKED} until every prerequisite quest
 * {@code COMPLETED}), progress through ordered {@code objectives}, carry a DM-only {@code twist} the DM
 * springs at the right beat, apply {@code completionImpact}/{@code failureImpact} guidance plus
 * {@code dispositionShifts} as real consequences, and pay out {@code reward} on completion.
 *
 * <p>{@code key}, each objective's {@code key}, {@code status}, and every {@code completed} flag are
 * engine-owned — the sanitizer forces the authored template back to its initial values.
 */
public record Quest(
        String key,
        String title,
        String summary,
        QuestType type,
        List<String> prerequisiteKeys,
        List<QuestObjective> objectives,
        String twist,
        String twistTrigger,
        QuestReward reward,
        String completionImpact,
        String failureImpact,
        List<QuestDispositionShift> dispositionShifts,
        QuestStatus status
) {
}
