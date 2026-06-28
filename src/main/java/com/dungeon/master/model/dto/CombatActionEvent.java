package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.CombatantKind;

import java.util.List;
import java.util.UUID;

/**
 * One actor's complete combat action, carried as a single event so the client can
 * sequence its whole animation without competing DICE_ROLL broadcasts: an actor
 * (player or enemy) resolves against one or more {@link Target targets} — a single
 * attack, a multiattack (several targets/sub-rolls), an AoE spell (many enemies),
 * or a heal (allies).
 *
 * <p>The client buffers these in arrival order and plays them back one at a time, so
 * the player sees their own action first, then each enemy's turn in initiative order.
 *
 * @param seq        monotonic per-action counter (stable ordering / de-dup on the client)
 * @param actionKind ATTACK | SPELL_DAMAGE | SPELL_HEAL | SPELL_EFFECT | ITEM
 * @param label      short verb phrase, e.g. "casts Fireball" / "attacks" / "uses Potion of Healing"
 */
public record CombatActionEvent(
        String type,
        UUID sessionId,
        long seq,
        CombatantKind actorKind,
        String actorName,
        String actionKind,
        String label,
        List<Target> targets,
        CombatStateDto combat
) {
    public static final String TYPE = "COMBAT_ACTION";

    /**
     * The resolution of the action against one target. Only the fields relevant to the
     * resolution are populated (an attack uses {@code attackRoll}/{@code vsAc}/{@code hit};
     * a save spell uses {@code saveRoll}/{@code saveDc}/{@code saved}; a heal uses
     * {@code heal}). {@code damageRoll}/{@code heal} are null when not applicable.
     */
    public record Target(
            CombatantKind targetKind,
            String targetName,
            RollSummary attackRoll,
            Integer vsAc,
            Boolean hit,
            RollSummary saveRoll,
            Integer saveDc,
            Boolean saved,
            RollSummary damageRoll,
            Integer heal,
            String condition,
            int currentHp,
            int maxHp,
            boolean defeated
    ) {
        /** A weapon/spell attack-roll result. */
        public static Target attack(CombatantKind kind, String name, RollSummary atk, int vsAc,
                                    boolean hit, RollSummary dmg, int curHp, int maxHp, boolean defeated) {
            return new Target(kind, name, atk, vsAc, hit, null, null, null, dmg, null, null,
                    curHp, maxHp, defeated);
        }

        /** A saving-throw spell result (full/half/zero damage by save). */
        public static Target save(CombatantKind kind, String name, RollSummary save, int dc,
                                  boolean saved, RollSummary dmg, int curHp, int maxHp, boolean defeated) {
            return new Target(kind, name, null, null, null, save, dc, saved, dmg, null, null,
                    curHp, maxHp, defeated);
        }

        /** An auto-hit damage result (e.g. Magic Missile). */
        public static Target autoDamage(CombatantKind kind, String name, RollSummary dmg,
                                        int curHp, int maxHp, boolean defeated) {
            return new Target(kind, name, null, null, true, null, null, null, dmg, null, null,
                    curHp, maxHp, defeated);
        }

        /** A heal result. */
        public static Target heal(CombatantKind kind, String name, int healed, int curHp, int maxHp) {
            return new Target(kind, name, null, null, null, null, null, null, null, healed, null,
                    curHp, maxHp, false);
        }

        /** A condition / buff applied (mostly narrative). */
        public static Target effect(CombatantKind kind, String name, String condition,
                                    int curHp, int maxHp) {
            return new Target(kind, name, null, null, null, null, null, null, null, null, condition,
                    curHp, maxHp, false);
        }
    }
}
