package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.entity.PlayerRuntimeState;

/**
 * The 5e dying / death-save MATH, applied directly to a {@link PlayerRuntimeState}. Stateless and
 * side-effect-free beyond mutating the passed entity — persistence and DTO mapping stay with
 * {@link PlayerStateService}. Keeping the death-save invariant here means there is exactly one place
 * the "drops to 0 → dying → dead" rules live.
 */
final class DyingRules {

    private DyingRules() {
    }

    /**
     * Heal up to max HP; returns HP actually restored. Any positive heal to a dying/stable creature
     * (0 HP, not dead) revives it — clearing {@code stable} and resetting both death-save counters.
     * You cannot heal the dead: a {@code dead} creature is a no-op.
     */
    static int heal(PlayerRuntimeState s, int amount) {
        if (s.isDead()) {
            return 0;                                        // cannot heal the dead
        }
        int amt = Math.max(0, amount);
        int before = s.getCurrentHp();
        if (before == 0 && amt > 0) {                        // revive a downed creature
            s.setStable(false);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
        }
        s.setCurrentHp(Math.min(s.getMaxHp(), before + amt));
        return s.getCurrentHp() - before;
    }

    /**
     * Apply damage, absorbing temp HP first; HP floors at 0. Implements 5e dying rules:
     * <ul>
     *   <li>A no-op once the creature is {@code dead}.</li>
     *   <li>Dropping a conscious creature to 0 HP makes it <em>dying</em> (resets death saves,
     *       clears stable); if the leftover damage past 0 is ≥ max HP it is <em>massive damage</em>
     *       → instant death.</li>
     *   <li>Damage to a creature already at 0 HP inflicts a death-save failure (two on a critical),
     *       and a stable creature reverts to dying. Damage ≥ max HP is instant death (the crit only
     *       affects the failure count, not the instant-death threshold). Three failures → dead.</li>
     * </ul>
     */
    static void damage(PlayerRuntimeState s, int amount, boolean critical) {
        if (s.isDead()) {
            return;                                          // already dead — nothing to do
        }
        int dmg = Math.max(0, amount);
        int absorbed = Math.min(s.getTempHp(), dmg);
        s.setTempHp(s.getTempHp() - absorbed);
        int remaining = dmg - absorbed;
        if (remaining <= 0) {
            return;                                          // fully soaked by temp HP
        }

        if (s.getCurrentHp() > 0) {
            int hpBefore = s.getCurrentHp();
            int leftover = remaining - hpBefore;             // damage past 0
            s.setCurrentHp(Math.max(0, hpBefore - remaining));
            if (s.getCurrentHp() == 0) {                     // dropped to 0 → dying
                s.setDeathSaveSuccesses(0);
                s.setDeathSaveFailures(0);
                s.setStable(false);
                if (leftover >= s.getMaxHp()) {              // massive damage → instant death
                    s.setDead(true);
                }
            }
        } else {                                             // already at 0 HP (dying or stable)
            boolean wasStable = s.isStable();
            s.setStable(false);                              // a stable creature reverts to dying
            if (remaining >= s.getMaxHp()) {                 // any damage ≥ max HP → instant death
                s.setDead(true);
                return;
            }
            if (wasStable) {                                 // re-downed: start a fresh dying state
                s.setDeathSaveSuccesses(0);
                s.setDeathSaveFailures(0);
            }
            s.setDeathSaveFailures(Math.min(3, s.getDeathSaveFailures() + (critical ? 2 : 1)));
            if (s.getDeathSaveFailures() >= 3) {
                s.setDead(true);
            }
        }
    }

    /**
     * Apply one death saving throw (raw d20, no modifiers) to a dying creature, mutating its
     * death-save state, and return the outcome. Natural 20 revives at 1 HP; natural 1 is two
     * failures; otherwise 10+ is a success and 9 or less a failure. Three successes stabilize; three
     * failures kill.
     */
    static PlayerStateService.DeathSaveOutcome recordDeathSave(PlayerRuntimeState s, DiceRollResult roll) {
        if (roll.crit()) {                                   // natural 20 → back on your feet at 1 HP
            s.setCurrentHp(Math.min(1, s.getMaxHp()));
            s.setStable(false);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
            return PlayerStateService.DeathSaveOutcome.REVIVED;
        } else if (roll.fumble()) {                          // natural 1 → two failures
            return addFailures(s, 2);
        } else if (roll.total() >= 10) {                     // 10+ → a success
            s.setDeathSaveSuccesses(Math.min(3, s.getDeathSaveSuccesses() + 1));
            if (s.getDeathSaveSuccesses() >= 3) {
                s.setStable(true);                           // stable: still 0 HP, stops rolling
                return PlayerStateService.DeathSaveOutcome.STABILIZED;
            }
            return PlayerStateService.DeathSaveOutcome.SUCCESS;
        } else {                                             // 9 or less → a failure
            return addFailures(s, 1);
        }
    }

    private static PlayerStateService.DeathSaveOutcome addFailures(PlayerRuntimeState s, int n) {
        s.setDeathSaveFailures(Math.min(3, s.getDeathSaveFailures() + n));
        if (s.getDeathSaveFailures() >= 3) {
            s.setDead(true);
            return PlayerStateService.DeathSaveOutcome.DIED;
        }
        return PlayerStateService.DeathSaveOutcome.FAILURE;
    }

    /**
     * Stabilize a dying creature: it stays at 0 HP and unconscious but stops rolling death saves.
     * Rejects targets that aren't dying. Returns {@code true} when it actually changed state (so the
     * caller knows to persist), {@code false} when already stable (idempotent no-op).
     */
    static boolean stabilize(PlayerRuntimeState s) {
        if (s.isDead()) {
            throw new IllegalStateException("Cannot stabilize a dead creature");
        }
        if (s.getCurrentHp() > 0) {
            throw new IllegalStateException("Target is conscious and does not need stabilizing");
        }
        if (!s.isStable()) {
            s.setStable(true);
            s.setDeathSaveSuccesses(0);
            s.setDeathSaveFailures(0);
            return true;
        }
        return false;
    }
}
