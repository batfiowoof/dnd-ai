package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.Quest;
import com.dungeon.master.model.dto.QuestDispositionShift;
import com.dungeon.master.model.dto.QuestObjective;
import com.dungeon.master.model.dto.QuestReward;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.QuestStatus;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CampaignMilestoneService.MilestoneResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Drives authored quests on a running session, the richer sibling of {@link CampaignMilestoneService}.
 * The DM names a quest (and objective) by key through {@code DmQuestTools}; this service validates it
 * against the session's compiled quest list, mutates the immutable {@link Quest} record in place, and —
 * on completion — pays rewards by REUSING existing engine paths: {@link PlayerStateService#addItem}
 * grants loot and coin (GP is an inventory item), {@link CampaignMilestoneService#completeMilestone}
 * levels the party when a milestone is linked, and {@link NpcStateService#adjust} applies disposition
 * shifts. Completing a quest recomputes the chain (unlocking dependents); failing one cascades failure
 * to dependents whose AND-prerequisites can no longer all complete.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuestService {

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final CampaignMilestoneService milestoneService;
    private final PlayerStateService playerStateService;
    private final NpcStateService npcStateService;

    /**
     * Outcome of a quest tool call. {@code changed} is true only when the call moved real state;
     * {@code effects} lists the human-readable consequences (rewards, level-ups, unlocked quests) for
     * the DM to narrate and broadcast; {@code note} explains rejections so the DM never lies to players.
     */
    public record QuestResult(String key, String title, QuestStatus status, boolean changed,
                              List<String> effects, String note) {
        static QuestResult rejected(String key, String note) {
            return new QuestResult(key, null, null, false, List.of(), note);
        }
    }

    /** Move an AVAILABLE quest to ACTIVE when the party takes it up. */
    @Transactional
    public QuestResult startQuest(UUID sessionId, String key) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return QuestResult.rejected(key, "no active session");
        }
        List<Quest> quests = new ArrayList<>(session.getQuests());
        int idx = indexOfKey(quests, key);
        if (idx < 0) {
            return QuestResult.rejected(key, "no authored quest with key '" + key + "'");
        }
        Quest q = quests.get(idx);
        if (q.status() == QuestStatus.LOCKED) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "quest is locked — its prerequisites are not complete");
        }
        if (q.status() != QuestStatus.AVAILABLE) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "quest is already " + q.status().name().toLowerCase(Locale.ROOT));
        }
        quests.set(idx, withStatus(q, QuestStatus.ACTIVE));
        session.setQuests(quests);
        sessionRepository.save(session);
        log.info("Quest '{}' started for session={}", q.key(), sessionId);
        return new QuestResult(q.key(), q.title(), QuestStatus.ACTIVE, true, List.of(), "quest started");
    }

    /** Tick one objective of an ACTIVE quest as complete. */
    @Transactional
    public QuestResult advanceQuest(UUID sessionId, String questKey, String objectiveKey) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return QuestResult.rejected(questKey, "no active session");
        }
        List<Quest> quests = new ArrayList<>(session.getQuests());
        int idx = indexOfKey(quests, questKey);
        if (idx < 0) {
            return QuestResult.rejected(questKey, "no authored quest with key '" + questKey + "'");
        }
        Quest q = quests.get(idx);
        if (q.status() != QuestStatus.ACTIVE) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "quest is not active — start it before advancing objectives");
        }
        List<QuestObjective> objectives = new ArrayList<>(q.objectives());
        int oIdx = indexOfObjective(objectives, objectiveKey);
        if (oIdx < 0) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "no objective with key '" + objectiveKey + "' on this quest");
        }
        QuestObjective o = objectives.get(oIdx);
        if (o.completed()) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "objective already complete");
        }
        objectives.set(oIdx, new QuestObjective(o.key(), o.description(), true));
        quests.set(idx, withObjectives(q, objectives));
        session.setQuests(quests);
        sessionRepository.save(session);
        long remaining = objectives.stream().filter(x -> !x.completed()).count();
        log.info("Quest '{}' objective '{}' completed for session={}, {} remaining",
                q.key(), o.key(), sessionId, remaining);
        return new QuestResult(q.key(), q.title(), QuestStatus.ACTIVE, true,
                List.of("Objective complete: " + o.description()),
                remaining == 0 ? "all objectives complete — the quest can be completed"
                        : remaining + " objective(s) remaining");
    }

    /**
     * Complete a quest: pay its rewards to the party, award any linked milestone, apply disposition
     * shifts, and unlock dependents whose prerequisites are now all complete.
     */
    @Transactional
    public QuestResult completeQuest(UUID sessionId, String questKey) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return QuestResult.rejected(questKey, "no active session");
        }
        List<Quest> quests = new ArrayList<>(session.getQuests());
        int idx = indexOfKey(quests, questKey);
        if (idx < 0) {
            return QuestResult.rejected(questKey, "no authored quest with key '" + questKey + "'");
        }
        Quest q = quests.get(idx);
        if (q.status() == QuestStatus.COMPLETED) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(), "quest already complete");
        }
        if (q.status() != QuestStatus.ACTIVE && q.status() != QuestStatus.AVAILABLE) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "quest cannot be completed from " + q.status().name().toLowerCase(Locale.ROOT));
        }

        List<String> effects = new ArrayList<>();
        List<Player> party = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> p.getRole() == PlayerRole.PLAYER)
                .toList();

        // Reward items — granted to each active party member. Coin items ("150 GP") are folded into the
        // numeric purse (copper) so they can be spent at shops; everything else lands in inventory.
        QuestReward reward = q.reward();
        if (reward != null && reward.items() != null) {
            for (InventoryItem item : reward.items()) {
                long coin = MoneyUtil.coinValueOf(item);
                int granted = 0;
                for (Player p : party) {
                    try {
                        if (coin > 0) {
                            playerStateService.addCoins(p.getId(), coin);
                        } else {
                            playerStateService.addItem(p.getId(), item);
                        }
                        granted++;
                    } catch (Exception e) {
                        log.debug("No runtime state to grant reward to player {}: {}", p.getId(), e.getMessage());
                    }
                }
                if (granted > 0) {
                    effects.add("Reward: " + (coin > 0 ? MoneyUtil.format(coin) : item.qty() + "× " + item.name()));
                }
            }
        }

        // Linked milestone → reuse the whole-party level-up path.
        if (reward != null && reward.milestoneKey() != null && !reward.milestoneKey().isBlank()) {
            MilestoneResult mr = milestoneService.completeMilestone(sessionId, reward.milestoneKey());
            if (mr.awarded()) {
                effects.add("The party advances a level (" + mr.title() + ")");
            }
        }

        // Real impact — nudge named NPCs' disposition toward the party.
        if (q.dispositionShifts() != null) {
            for (QuestDispositionShift shift : q.dispositionShifts()) {
                NpcStateService.AdjustResult ar = npcStateService.adjust(sessionId, shift.npcName(), shift.delta());
                if (ar.found() && ar.changed()) {
                    effects.add(ar.name() + " now feels " + ar.band().toLowerCase(Locale.ROOT)
                            + " toward the party");
                }
            }
        }

        // Reload the (managed) session — completeMilestone above may have mutated milestones on it — and
        // mark the quest complete, then unlock any dependents whose prerequisites are now all complete.
        quests = new ArrayList<>(session.getQuests());
        quests.set(idx, withStatus(q, QuestStatus.COMPLETED));
        List<String> unlocked = unlockDependents(quests);
        effects.addAll(unlocked);
        session.setQuests(quests);
        sessionRepository.save(session);

        log.info("Quest '{}' completed for session={} ({} effects, {} unlocked)",
                q.key(), sessionId, effects.size(), unlocked.size());
        return new QuestResult(q.key(), q.title(), QuestStatus.COMPLETED, true, effects,
                blankToNull(q.completionImpact()));
    }

    /** Fail a quest and cascade failure to dependents whose AND-prerequisites can no longer complete. */
    @Transactional
    public QuestResult failQuest(UUID sessionId, String questKey, String reason) {
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return QuestResult.rejected(questKey, "no active session");
        }
        List<Quest> quests = new ArrayList<>(session.getQuests());
        int idx = indexOfKey(quests, questKey);
        if (idx < 0) {
            return QuestResult.rejected(questKey, "no authored quest with key '" + questKey + "'");
        }
        Quest q = quests.get(idx);
        if (q.status() == QuestStatus.COMPLETED || q.status() == QuestStatus.FAILED) {
            return new QuestResult(q.key(), q.title(), q.status(), false, List.of(),
                    "quest is already " + q.status().name().toLowerCase(Locale.ROOT));
        }
        quests.set(idx, withStatus(q, QuestStatus.FAILED));
        List<String> cascaded = cascadeFailure(quests);
        session.setQuests(quests);
        sessionRepository.save(session);

        List<String> effects = new ArrayList<>(cascaded);
        log.info("Quest '{}' failed for session={} (reason={}, {} dependents blocked)",
                q.key(), sessionId, reason, cascaded.size());
        return new QuestResult(q.key(), q.title(), QuestStatus.FAILED, true, effects,
                blankToNull(q.failureImpact()));
    }

    /* ── chain resolution ────────────────────────────────────────── */

    /** Flip every LOCKED quest whose prerequisites are all COMPLETED to AVAILABLE. Returns descriptions. */
    private static List<String> unlockDependents(List<Quest> quests) {
        Set<String> completed = keysWithStatus(quests, QuestStatus.COMPLETED);
        List<String> unlocked = new ArrayList<>();
        for (int i = 0; i < quests.size(); i++) {
            Quest q = quests.get(i);
            if (q.status() == QuestStatus.LOCKED && prerequisitesMet(q, completed)) {
                quests.set(i, withStatus(q, QuestStatus.AVAILABLE));
                unlocked.add("New quest available: " + q.title());
            }
        }
        return unlocked;
    }

    /** Fail (to a fixpoint) any not-yet-resolved quest that has a FAILED prerequisite. Returns descriptions. */
    private static List<String> cascadeFailure(List<Quest> quests) {
        List<String> blocked = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> failed = keysWithStatus(quests, QuestStatus.FAILED);
            for (int i = 0; i < quests.size(); i++) {
                Quest q = quests.get(i);
                if (q.status() == QuestStatus.LOCKED || q.status() == QuestStatus.AVAILABLE) {
                    boolean dependsOnFailed = q.prerequisiteKeys() != null && q.prerequisiteKeys().stream()
                            .anyMatch(k -> failed.contains(k.toLowerCase(Locale.ROOT)));
                    if (dependsOnFailed) {
                        quests.set(i, withStatus(q, QuestStatus.FAILED));
                        blocked.add("Quest blocked: " + q.title() + " (a prerequisite failed)");
                        changed = true;
                    }
                }
            }
        }
        return blocked;
    }

    private static boolean prerequisitesMet(Quest q, Set<String> completedKeys) {
        if (q.prerequisiteKeys() == null || q.prerequisiteKeys().isEmpty()) {
            return true;
        }
        return q.prerequisiteKeys().stream()
                .allMatch(k -> completedKeys.contains(k.toLowerCase(Locale.ROOT)));
    }

    private static Set<String> keysWithStatus(List<Quest> quests, QuestStatus status) {
        Set<String> keys = new HashSet<>();
        for (Quest q : quests) {
            if (q.status() == status && q.key() != null) {
                keys.add(q.key().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    /* ── record copy-with helpers (records are immutable) ────────── */

    private static Quest withStatus(Quest q, QuestStatus status) {
        return new Quest(q.key(), q.title(), q.summary(), q.type(), q.prerequisiteKeys(), q.objectives(),
                q.twist(), q.twistTrigger(), q.reward(), q.completionImpact(), q.failureImpact(),
                q.dispositionShifts(), status);
    }

    private static Quest withObjectives(Quest q, List<QuestObjective> objectives) {
        return new Quest(q.key(), q.title(), q.summary(), q.type(), q.prerequisiteKeys(), objectives,
                q.twist(), q.twistTrigger(), q.reward(), q.completionImpact(), q.failureImpact(),
                q.dispositionShifts(), q.status());
    }

    private static int indexOfKey(List<Quest> quests, String key) {
        if (key == null) {
            return -1;
        }
        String want = key.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < quests.size(); i++) {
            String k = quests.get(i).key();
            if (k != null && k.trim().toLowerCase(Locale.ROOT).equals(want)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfObjective(List<QuestObjective> objectives, String key) {
        if (key == null) {
            return -1;
        }
        String want = key.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < objectives.size(); i++) {
            String k = objectives.get(i).key();
            if (k != null && k.trim().toLowerCase(Locale.ROOT).equals(want)) {
                return i;
            }
        }
        return -1;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
