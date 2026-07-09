package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.MonsterAction;

import java.util.Comparator;
import java.util.List;

/**
 * Stateless rules for legendary and lair actions: which option a boss spends its points on, and
 * where in the initiative order a lair action fires. Pure math and selection — all dice, HP changes
 * and broadcasting stay in {@code CombatService}, mirroring {@link CombatMath}.
 */
public final class MonsterActionRules {

    /** Lair actions fire on initiative count 20, losing initiative ties. */
    public static final int LAIR_INITIATIVE = 20;

    private MonsterActionRules() {}

    /**
     * Pick the legendary action to spend on. Prefers the most expensive option the remaining budget
     * can still afford, so a boss opens with its dramatic 2-point move rather than dribbling the
     * budget away on cheap ones; ties break on the authored order. Returns {@code null} when nothing
     * is affordable (or there are no options), which leaves the remaining points unspent.
     */
    public static MonsterAction chooseLegendary(List<MonsterAction> options, int pointsRemaining) {
        if (options == null || options.isEmpty() || pointsRemaining <= 0) {
            return null;
        }
        return options.stream()
                .filter(a -> a.pointCost() <= pointsRemaining)
                .max(Comparator.comparingInt(MonsterAction::pointCost))
                .orElse(null);
    }

    /**
     * Pick this round's lair action by rotating through the authored options, so a long fight does
     * not repeat the same effect every round. Returns {@code null} when there are no options.
     */
    public static MonsterAction chooseLair(List<MonsterAction> options, int round) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        return options.get(Math.floorMod(round - 1, options.size()));
    }

    /**
     * The index in the initiative order at which the lair action resolves: immediately before the
     * first combatant slower than initiative count 20 (so anyone who rolled 20 or better acts first
     * — the lair loses ties).
     *
     * <p>When every combatant rolled 20 or better there is no such slot, and an index of
     * {@code order.size()} could never be reached by the active pointer — the lair action would
     * silently never fire. Clamp to the last combatant instead, so the lair still acts once per
     * round (just before the slowest creature rather than strictly after it).
     *
     * @return the slot index, or {@code -1} when the order is empty
     */
    public static int lairSlot(List<Combatant> order) {
        if (order == null || order.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).initiative() < LAIR_INITIATIVE) {
                return i;
            }
        }
        return order.size() - 1;
    }
}
