package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.MonsterAction;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.enums.MonsterActionKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the hand-authored {@code monster-actions.json} overlay merges onto the generated
 * stat blocks, and that its cross-references (attack names, condition keys) actually resolve.
 * These are the failure modes a typo in the data file would otherwise only surface mid-combat.
 */
class MonsterCatalogLegendaryTest {

    /** Condition keys the engine understands ({@code ConditionRules}). */
    private static final Set<String> VALID_CONDITIONS = Set.of(
            "blinded", "charmed", "frightened", "grappled", "incapacitated", "paralyzed",
            "petrified", "poisoned", "prone", "restrained", "stunned", "unconscious");

    private static MonsterCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = new MonsterCatalog();
        catalog.load();
    }

    private static MonsterTemplate require(String key) {
        return catalog.get(key).orElseThrow(() -> new AssertionError("missing monster " + key));
    }

    private static List<MonsterTemplate> legendaryMonsters() {
        return catalog.keys().stream().map(MonsterCatalogLegendaryTest::require)
                .filter(MonsterTemplate::isLegendary)
                .toList();
    }

    @Test
    void mergesTheCuratedOverlayOntoTheGeneratedStatBlocks() {
        MonsterTemplate dragon = require("ANCIENT_RED_DRAGON");
        assertTrue(dragon.isLegendary(), "the ancient red dragon should take legendary actions");
        assertTrue(dragon.hasLair(), "the ancient red dragon should have lair actions");
        assertEquals(3, dragon.legendaryActionMax());
        assertEquals(4, dragon.legendaryResistances());
        // The stat block itself still parsed — the overlay is additive, not a replacement.
        assertEquals(507, dragon.hp());
        assertFalse(dragon.attacks().isEmpty());
    }

    @Test
    void coversTheCuratedSetOfBosses() {
        assertEquals(26, legendaryMonsters().size(),
                "20 Adult/Ancient dragons + Lich, Vampire, Kraken, Tarrasque, Aboleth, Mummy Lord");
        for (String key : List.of("LICH", "VAMPIRE", "KRAKEN", "TARRASQUE", "ABOLETH", "MUMMY_LORD",
                "ADULT_BLACK_DRAGON", "ANCIENT_GOLD_DRAGON")) {
            assertTrue(require(key).isLegendary(), key + " should be legendary");
        }
    }

    @Test
    void ordinaryMonstersCarryNoBossMechanics() {
        MonsterTemplate goblin = catalog.summaries().stream()
                .filter(s -> s.cr() != null && s.cr() <= 1)
                .findFirst().map(s -> require(s.key())).orElseThrow();
        assertFalse(goblin.isLegendary());
        assertFalse(goblin.hasLair());
        assertEquals(0, goblin.legendaryResistances());
    }

    @Test
    void tarrasqueIsLegendaryButHasNoLair() {
        MonsterTemplate t = require("TARRASQUE");
        assertTrue(t.isLegendary());
        assertFalse(t.hasLair(), "the tarrasque roams — it has no lair to fight it in");
    }

    /**
     * The one cross-file reference that can silently rot: an ATTACK action names an attack from the
     * monster's own stat block, and {@code resolveMonsterAttackAction} finds it by name.
     */
    @Test
    void everyAttackActionNamesARealAttackOnItsOwnStatBlock() {
        for (MonsterTemplate m : legendaryMonsters()) {
            Set<String> names = m.attacks().stream()
                    .map(a -> a.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            for (MonsterAction a : allActions(m)) {
                if (a.kind() == MonsterActionKind.ATTACK) {
                    assertNotNull(a.attackName(), m.key() + " / " + a.name() + " has no attackName");
                    assertTrue(names.contains(a.attackName().toLowerCase(Locale.ROOT)),
                            m.key() + " / " + a.name() + " references unknown attack '"
                                    + a.attackName() + "' (has " + names + ")");
                }
            }
        }
    }

    @Test
    void everySaveActionIsResolvableAndEveryConditionIsKnownToTheEngine() {
        for (MonsterTemplate m : legendaryMonsters()) {
            for (MonsterAction a : allActions(m)) {
                if (a.kind() == MonsterActionKind.SAVE) {
                    assertNotNull(a.saveAbility(), m.key() + " / " + a.name() + " has no saveAbility");
                    assertNotNull(a.saveDc(), m.key() + " / " + a.name() + " has no saveDc");
                }
                if (a.condition() != null) {
                    assertTrue(VALID_CONDITIONS.contains(a.condition()),
                            m.key() + " / " + a.name() + " applies unknown condition " + a.condition());
                }
            }
        }
    }

    /**
     * Every boss must have a 1-point option, or a leftover point could never be spent — the budget
     * would strand and the boss would silently skip its last legendary action each round.
     */
    @Test
    void everyBossHasAOnePointLegendaryOption() {
        for (MonsterTemplate m : legendaryMonsters()) {
            assertTrue(m.legendaryActions().stream().anyMatch(a -> a.pointCost() == 1),
                    m.key() + " has no 1-point legendary action");
            assertTrue(m.legendaryActions().stream().allMatch(a -> a.pointCost() <= m.legendaryActionMax()),
                    m.key() + " has a legendary action nobody can ever afford");
        }
    }

    /** The summary the host's encounter picker reads must flag lair-capable monsters. */
    @Test
    void summariesFlagLairCapableMonstersForThePicker() {
        MonsterCatalog.MonsterSummary dragon = catalog.summaries().stream()
                .filter(s -> s.key().equals("ANCIENT_RED_DRAGON")).findFirst().orElseThrow();
        assertTrue(dragon.hasLair());
        MonsterCatalog.MonsterSummary tarrasque = catalog.summaries().stream()
                .filter(s -> s.key().equals("TARRASQUE")).findFirst().orElseThrow();
        assertFalse(tarrasque.hasLair());
    }

    private static List<MonsterAction> allActions(MonsterTemplate m) {
        return java.util.stream.Stream.concat(m.legendaryActions().stream(), m.lairActions().stream())
                .toList();
    }
}
