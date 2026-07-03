package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.Quest;
import com.dungeon.master.model.dto.QuestDispositionShift;
import com.dungeon.master.model.dto.QuestObjective;
import com.dungeon.master.model.dto.QuestReward;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.QuestStatus;
import com.dungeon.master.model.enums.QuestType;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.CampaignMilestoneService.MilestoneResult;
import com.dungeon.master.service.game.NpcStateService.AdjustResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Quest lifecycle: start/advance/complete/fail with reward delegation, chain unlock, and cascade. */
class QuestServiceTest {

    private GameSessionRepository sessionRepository;
    private PlayerRepository playerRepository;
    private CampaignMilestoneService milestoneService;
    private PlayerStateService playerStateService;
    private NpcStateService npcStateService;
    private QuestService service;

    private final UUID sessionId = UUID.randomUUID();
    private GameSession session;

    @BeforeEach
    void setup() {
        sessionRepository = mock(GameSessionRepository.class);
        playerRepository = mock(PlayerRepository.class);
        milestoneService = mock(CampaignMilestoneService.class);
        playerStateService = mock(PlayerStateService.class);
        npcStateService = mock(NpcStateService.class);
        service = new QuestService(sessionRepository, playerRepository, milestoneService,
                playerStateService, npcStateService);

        // find-relic: available, one objective, grants coin + a linked milestone + a disposition shift.
        Quest findRelic = new Quest("find-relic", "Find the Relic", "", QuestType.MAIN,
                List.of(),
                new ArrayList<>(List.of(new QuestObjective("reach-temple", "Reach the temple", false))),
                "", "",
                new QuestReward("", List.of(new InventoryItem("150 GP", 1, ItemKind.GEAR, false)), "level-2"),
                "the temple is saved", "the temple falls",
                List.of(new QuestDispositionShift("Elder", 20)),
                QuestStatus.AVAILABLE);
        // slay-dragon: locked behind find-relic.
        Quest slayDragon = new Quest("slay-dragon", "Slay the Dragon", "", QuestType.MAIN,
                List.of("find-relic"), List.of(), "", "",
                new QuestReward("", List.of(), null), "", "", List.of(), QuestStatus.LOCKED);

        session = GameSession.builder()
                .id(sessionId)
                .quests(new ArrayList<>(List.of(findRelic, slayDragon)))
                .build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Player player = Player.builder().sessionId(sessionId).role(PlayerRole.PLAYER).build();
        when(playerRepository.findBySessionId(sessionId)).thenReturn(List.of(player));
        when(milestoneService.completeMilestone(any(), any()))
                .thenReturn(new MilestoneResult("level-2", "Level 2", true, List.of("Hero"), "leveled"));
        when(npcStateService.adjust(any(), any(), anyInt()))
                .thenReturn(new AdjustResult(true, "Elder", 20, "Friendly", true));
    }

    @Test
    void startingAnAvailableQuestActivatesIt() {
        var result = service.startQuest(sessionId, "find-relic");
        assertTrue(result.changed());
        assertEquals(QuestStatus.ACTIVE, result.status());
    }

    @Test
    void startingALockedQuestIsRejected() {
        var result = service.startQuest(sessionId, "slay-dragon");
        assertFalse(result.changed());
        assertEquals(QuestStatus.LOCKED, result.status());
    }

    @Test
    void completingAQuestPaysRewardsLevelsUpAndUnlocksDependents() {
        service.startQuest(sessionId, "find-relic");
        service.advanceQuest(sessionId, "find-relic", "reach-temple");
        var result = service.completeQuest(sessionId, "find-relic");

        assertEquals(QuestStatus.COMPLETED, result.status());
        // The "150 GP" reward is coin, so it credits the numeric purse (15000 cp) rather than inventory.
        verify(playerStateService).addCoins(any(), eq(15000L));
        verify(milestoneService).completeMilestone(sessionId, "level-2"); // linked milestone awarded
        verify(npcStateService).adjust(eq(sessionId), eq("Elder"), eq(20)); // real impact applied

        Quest slay = questByKey("slay-dragon");
        assertEquals(QuestStatus.AVAILABLE, slay.status(), "dependent unlocks once its prerequisite completes");
    }

    @Test
    void failingAQuestCascadesToDependents() {
        var result = service.failQuest(sessionId, "find-relic", "the relic was destroyed");

        assertEquals(QuestStatus.FAILED, result.status());
        Quest slay = questByKey("slay-dragon");
        assertEquals(QuestStatus.FAILED, slay.status(), "a quest whose prerequisite failed can never open");
    }

    private Quest questByKey(String key) {
        return session.getQuests().stream()
                .filter(q -> q.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
