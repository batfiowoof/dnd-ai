package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.Quest;
import com.dungeon.master.model.dto.QuestDispositionShift;
import com.dungeon.master.model.dto.QuestObjective;
import com.dungeon.master.model.dto.QuestReward;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.QuestStatus;
import com.dungeon.master.model.enums.QuestType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates server-side cleaning of authored world content. */
class WorldSanitizerTest {

    private final WorldSanitizer sanitizer = new WorldSanitizer();

    @Test
    void normalizeMilestonesDropsBlanksDuplicatesAndForcesIncomplete() {
        List<Milestone> result = sanitizer.normalizeMilestones(List.of(
                new Milestone("claim-ship", "Masters of the Tide", "secure a vessel", true),
                new Milestone("  ", "blank key", "x", false),
                new Milestone("CLAIM-SHIP", "dupe key", "y", false)));

        assertEquals(1, result.size());
        assertEquals("claim-ship", result.get(0).key());
        assertFalse(result.get(0).completed(), "client can never mark a milestone done");
    }

    @Test
    void sanitizeMonstersNamespacesKeyAndKeepsCombatLegalBlocks() {
        CustomMonster valid = new CustomMonster(
                null, "Ash Wraith", "Medium", "Undead", 2.0, 13, 22, "5d8", 30, 2,
                Map.of("STR", 8, "DEX", 14),
                List.of(new MonsterAttack("Claw", "MELEE", 4, 5, null, "2d6", "Necrotic")),
                null);

        List<CustomMonster> result = sanitizer.sanitizeMonsters(List.of(valid));

        assertEquals(1, result.size());
        assertTrue(result.get(0).key().startsWith(WorldSanitizer.CUSTOM_KEY_PREFIX));
        assertEquals("CUSTOM_ASH_WRAITH", result.get(0).key());
    }

    @Test
    void sanitizeMonstersDropsBlocksMissingAcHpOrAttacks() {
        CustomMonster noAttack = new CustomMonster(
                null, "Toothless", "Small", "Beast", 0.0, 10, 5, null, 30, 0,
                Map.of(), List.of(), null);
        CustomMonster noAc = new CustomMonster(
                null, "Ghost", "Medium", "Undead", 1.0, null, 10, null, 30, 0,
                Map.of(), List.of(new MonsterAttack("Touch", "MELEE", 3, 5, null, "1d6", "Cold")),
                null);

        assertTrue(sanitizer.sanitizeMonsters(List.of(noAttack, noAc)).isEmpty(),
                "monsters without AC/HP/attack are not combat-legal and must be dropped");
    }

    @Test
    void normalizeQuestsForcesInitialStatusFromPrerequisitesAndCleansNested() {
        Quest gather = new Quest("gather-allies", "Gather Allies", "rally the town", QuestType.MAIN,
                List.of(),
                List.of(new QuestObjective("", "Convince the mayor", true)),
                "a betrayal", "at the feast",
                new QuestReward("coin", List.of(new InventoryItem("100 GP", 0, null, false)), "  m1 "),
                "town rallies", "town falls",
                List.of(new QuestDispositionShift("Mira", 500)),
                QuestStatus.COMPLETED);
        // Prerequisites include itself and a blank — both must be dropped; status becomes LOCKED.
        Quest finale = new Quest("final-battle", "Final Battle", "", null,
                List.of("gather-allies", "final-battle", "  "),
                List.of(), "", "", null, "", "", List.of(), QuestStatus.ACTIVE);
        Quest blankKey = new Quest("  ", "No Key", "", QuestType.SIDE, List.of(), List.of(),
                "", "", null, "", "", List.of(), QuestStatus.AVAILABLE);
        Quest dupe = new Quest("GATHER-ALLIES", "Dupe", "", QuestType.SIDE, List.of(), List.of(),
                "", "", null, "", "", List.of(), QuestStatus.AVAILABLE);

        List<Quest> result = sanitizer.normalizeQuests(List.of(gather, finale, blankKey, dupe));

        assertEquals(2, result.size(), "blank-key and duplicate-key quests are dropped");
        Quest g = result.get(0);
        assertEquals("gather-allies", g.key());
        assertEquals(QuestStatus.AVAILABLE, g.status(), "no prerequisites → available, never the client's status");
        assertEquals(1, g.objectives().size());
        assertFalse(g.objectives().get(0).completed(), "objectives always reset to not-complete");
        assertFalse(g.objectives().get(0).key().isBlank(), "objective key is backfilled");
        assertEquals(1, g.reward().items().get(0).qty(), "item qty is floored at 1");
        assertEquals(ItemKind.GEAR, g.reward().items().get(0).kind(), "null item kind defaults to GEAR");
        assertEquals("m1", g.reward().milestoneKey(), "milestone key is trimmed");
        assertEquals(100, g.dispositionShifts().get(0).delta(), "disposition delta is clamped to +/-100");

        Quest f = result.get(1);
        assertEquals(QuestStatus.LOCKED, f.status(), "having a prerequisite forces LOCKED");
        assertEquals(List.of("gather-allies"), f.prerequisiteKeys(), "self and blank prerequisites dropped");
    }
}
