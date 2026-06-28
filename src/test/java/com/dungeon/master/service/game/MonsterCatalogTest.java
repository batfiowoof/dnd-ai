package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.MonsterTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the bundled monsters.json loads into combat-ready stat blocks. */
class MonsterCatalogTest {

    private static MonsterCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = new MonsterCatalog();
        catalog.load();
    }

    @Test
    void loadsManyMonsters() {
        assertFalse(catalog.isEmpty(), "monsters.json should load");
        assertTrue(catalog.keys().size() > 100, "expected the full SRD bestiary");
    }

    @Test
    void everyLoadedMonsterIsCombatReady() {
        for (String key : catalog.keys()) {
            MonsterTemplate t = catalog.get(key).orElseThrow();
            assertNotNull(t.ac(), key + " needs AC");
            assertNotNull(t.hp(), key + " needs HP");
            assertFalse(t.attacks().isEmpty(), key + " needs an attack");
        }
    }

    @Test
    void goblinWarriorHasExpectedStats() {
        Optional<MonsterTemplate> g = catalog.get("GOBLIN_WARRIOR");
        assertTrue(g.isPresent(), "GOBLIN_WARRIOR should exist in SRD 5.2.1");
        MonsterTemplate t = g.get();
        assertEquals(15, t.ac());
        assertTrue(t.attacks().size() >= 1);
        assertNotNull(t.attacks().get(0).damageDice());
    }

    @Test
    void summariesSortByChallengeRating() {
        var summaries = catalog.summaries();
        for (int i = 1; i < summaries.size(); i++) {
            double prev = summaries.get(i - 1).cr() == null ? 99 : summaries.get(i - 1).cr();
            double cur = summaries.get(i).cr() == null ? 99 : summaries.get(i).cr();
            assertTrue(prev <= cur, "summaries should be CR-ascending");
        }
    }
}
