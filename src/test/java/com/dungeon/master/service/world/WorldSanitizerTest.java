package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.MonsterAttack;
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
}
