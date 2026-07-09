package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.MonsterAction;
import com.dungeon.master.model.enums.CombatantKind;
import com.dungeon.master.model.enums.MonsterActionKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Legendary-action budgeting and the initiative-count-20 lair slot. */
class MonsterActionRulesTest {

    private static MonsterAction action(String name, int cost) {
        return new MonsterAction(name, cost, MonsterActionKind.NARRATIVE, null, null, null,
                null, null, false, null, null, null, null);
    }

    private static Combatant combatant(CombatantKind kind, int initiative) {
        return new Combatant(kind, UUID.randomUUID(), "c" + initiative, initiative, 0);
    }

    private static final List<MonsterAction> DRAGON =
            List.of(action("Detect", 1), action("Tail Attack", 1), action("Wing Attack", 2));

    /* ── chooseLegendary ─────────────────────────────────────────── */

    @Test
    void spendsThePriciestAffordableOption() {
        assertEquals("Wing Attack", MonsterActionRules.chooseLegendary(DRAGON, 3).name());
        assertEquals("Wing Attack", MonsterActionRules.chooseLegendary(DRAGON, 2).name());
    }

    @Test
    void fallsBackToACheapOptionWhenTheBudgetIsThin() {
        // One point left: the 2-point Wing Attack is out of reach.
        assertEquals(1, MonsterActionRules.chooseLegendary(DRAGON, 1).pointCost());
    }

    @Test
    void choosesNothingWithoutBudgetOrOptions() {
        assertNull(MonsterActionRules.chooseLegendary(DRAGON, 0));
        assertNull(MonsterActionRules.chooseLegendary(DRAGON, -1));
        assertNull(MonsterActionRules.chooseLegendary(List.of(), 3));
        assertNull(MonsterActionRules.chooseLegendary(null, 3));
        // Every option costs more than the remaining point.
        assertNull(MonsterActionRules.chooseLegendary(List.of(action("Wing Attack", 2)), 1));
    }

    @Test
    void aZeroCostOptionStillCostsAPointSoTheBudgetAlwaysDrains() {
        assertEquals(1, action("Malformed", 0).pointCost());
    }

    /* ── chooseLair ──────────────────────────────────────────────── */

    @Test
    void rotatesLairActionsAcrossRoundsSoAFightDoesNotRepeatItself() {
        List<MonsterAction> lair = List.of(action("Grasping Earth", 1), action("Volcanic Gases", 1));
        assertEquals("Grasping Earth", MonsterActionRules.chooseLair(lair, 1).name());
        assertEquals("Volcanic Gases", MonsterActionRules.chooseLair(lair, 2).name());
        assertEquals("Grasping Earth", MonsterActionRules.chooseLair(lair, 3).name());
        assertNull(MonsterActionRules.chooseLair(List.of(), 1));
    }

    /* ── lairSlot ────────────────────────────────────────────────── */

    @Test
    void lairActsAfterAnyoneWhoRolledTwentyOrBetter() {
        // [Player(22), Dragon(19), Player(14)] — the lair loses ties, so it slots in at index 1.
        List<Combatant> order = List.of(
                combatant(CombatantKind.PLAYER, 22),
                combatant(CombatantKind.ENEMY, 19),
                combatant(CombatantKind.PLAYER, 14));
        assertEquals(1, MonsterActionRules.lairSlot(order));
    }

    @Test
    void lairLosesTiesAgainstAnExactTwenty() {
        List<Combatant> order = List.of(
                combatant(CombatantKind.PLAYER, 20),
                combatant(CombatantKind.ENEMY, 19));
        assertEquals(1, MonsterActionRules.lairSlot(order));
    }

    @Test
    void lairActsFirstWhenNobodyReachedTwenty() {
        List<Combatant> order = List.of(
                combatant(CombatantKind.PLAYER, 18),
                combatant(CombatantKind.ENEMY, 7));
        assertEquals(0, MonsterActionRules.lairSlot(order));
    }

    @Test
    void lairStillActsWhenEveryCombatantRolledTwentyOrBetter() {
        // Without a clamp the slot would be order.size(), which the active index can never reach —
        // the lair action would silently never fire for the whole encounter.
        List<Combatant> order = List.of(
                combatant(CombatantKind.PLAYER, 25),
                combatant(CombatantKind.ENEMY, 21),
                combatant(CombatantKind.PLAYER, 20));
        assertEquals(order.size() - 1, MonsterActionRules.lairSlot(order));
    }

    @Test
    void anEmptyOrderHasNoLairSlot() {
        assertEquals(-1, MonsterActionRules.lairSlot(List.of()));
        assertEquals(-1, MonsterActionRules.lairSlot(null));
    }
}
