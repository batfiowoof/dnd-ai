package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.enums.RollMode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, stateless SRD 5.2.1 condition mechanics — the single place that turns a combatant's
 * {@link ActiveCondition} list into combat effects (advantage/disadvantage, auto-fails,
 * incapacitation, speed). Mirrors {@code SpellcastingRules}: no Spring, no state, just rules.
 *
 * <p>Attack-roll advantage and disadvantage are combined per RAW via {@link RollMode#combine}
 * (a source of each cancels to NORMAL). Bane/Bless are modelled as flat ±2 numeric modifiers
 * rather than ±1d4 for deterministic resolution (documented simplification).</p>
 */
public final class ConditionRules {

    private ConditionRules() {}

    /* ── canonical condition keys ────────────────────────────────────── */

    public static final String BLINDED = "blinded";
    public static final String CHARMED = "charmed";
    public static final String FRIGHTENED = "frightened";
    public static final String GRAPPLED = "grappled";
    public static final String INCAPACITATED = "incapacitated";
    public static final String PARALYZED = "paralyzed";
    public static final String PETRIFIED = "petrified";
    public static final String POISONED = "poisoned";
    public static final String PRONE = "prone";
    public static final String RESTRAINED = "restrained";
    public static final String STUNNED = "stunned";
    public static final String UNCONSCIOUS = "unconscious";

    // Spell-specific pseudo-conditions.
    public static final String FAERIE_FIRE = "faerie-fire";
    public static final String BANED = "baned";
    public static final String BLESSED = "blessed";
    public static final String SLOWED = "slowed";
    public static final String ENFEEBLED = "enfeebled";

    /** Conditions that cost the creature its turn (can take no actions/reactions). */
    private static final Set<String> INCAPACITATING =
            Set.of(INCAPACITATED, PARALYZED, PETRIFIED, STUNNED, UNCONSCIOUS);

    /** Conditions that drop the creature's movement to 0. */
    private static final Set<String> SPEED_ZERO =
            Set.of(GRAPPLED, RESTRAINED, PARALYZED, PETRIFIED, STUNNED, UNCONSCIOUS);

    /** Defender conditions that hand attackers advantage outright. */
    private static final Set<String> ATTACKED_AT_ADVANTAGE =
            Set.of(BLINDED, PARALYZED, PETRIFIED, RESTRAINED, STUNNED, UNCONSCIOUS, FAERIE_FIRE);

    /** Attacker conditions that impose disadvantage on its own attack rolls. */
    private static final Set<String> ATTACKS_AT_DISADVANTAGE =
            Set.of(BLINDED, FRIGHTENED, POISONED, PRONE, RESTRAINED, ENFEEBLED);

    /** Defender conditions vs which a melee hit is an automatic critical (attacker within 5 ft). */
    private static final Set<String> AUTO_CRIT_MELEE = Set.of(PARALYZED, UNCONSCIOUS);

    /* ── turn / movement ─────────────────────────────────────────────── */

    /** True when the creature cannot act this turn (its turn should be skipped). */
    public static boolean incapacitated(List<ActiveCondition> conds) {
        return anyOf(conds, INCAPACITATING);
    }

    /** Effective movement budget given the creature's conditions (0 if rooted, halved if slowed). */
    public static int effectiveSpeed(int baseSpeed, List<ActiveCondition> conds) {
        if (anyOf(conds, SPEED_ZERO)) {
            return 0;
        }
        if (has(conds, SLOWED)) {
            return baseSpeed / 2;
        }
        return baseSpeed;
    }

    /* ── attack resolution ───────────────────────────────────────────── */

    /**
     * Net roll mode for an attack from {@code attacker} against {@code defender}.
     * Folds the attacker's own conditions, the defender's conditions, and the melee/ranged
     * distinction (prone: melee → advantage, ranged → disadvantage) using RAW cancellation.
     */
    public static RollMode attackMode(List<ActiveCondition> attacker,
                                      List<ActiveCondition> defender, boolean melee) {
        boolean advantage = anyOf(defender, ATTACKED_AT_ADVANTAGE)
                || (has(defender, PRONE) && melee);
        boolean disadvantage = anyOf(attacker, ATTACKS_AT_DISADVANTAGE)
                || (has(defender, PRONE) && !melee);
        return RollMode.combine(advantage ? RollMode.ADVANTAGE : RollMode.NORMAL,
                disadvantage ? RollMode.DISADVANTAGE : RollMode.NORMAL);
    }

    /** A melee hit against a paralyzed/unconscious creature within 5 ft is an automatic critical. */
    public static boolean autoCritMelee(List<ActiveCondition> defender, boolean melee) {
        return melee && anyOf(defender, AUTO_CRIT_MELEE);
    }

    /** Flat modifier applied to the attacker's d20 attack roll (Bless +2, Bane −2; they stack). */
    public static int attackModifier(List<ActiveCondition> conds) {
        return rollAdjust(conds);
    }

    /* ── saving throws ───────────────────────────────────────────────── */

    /** Paralyzed/stunned/petrified/unconscious creatures automatically fail STR and DEX saves. */
    public static boolean autoFailsSave(List<ActiveCondition> conds, String ability) {
        if (ability == null) {
            return false;
        }
        String a = ability.toUpperCase(Locale.ROOT);
        if (!a.equals("STR") && !a.equals("DEX")) {
            return false;
        }
        return has(conds, PARALYZED) || has(conds, STUNNED)
                || has(conds, PETRIFIED) || has(conds, UNCONSCIOUS);
    }

    /** Roll mode for a saving throw (restrained → disadvantage on DEX saves). */
    public static RollMode saveMode(List<ActiveCondition> conds, String ability) {
        if (ability != null && ability.equalsIgnoreCase("DEX") && has(conds, RESTRAINED)) {
            return RollMode.DISADVANTAGE;
        }
        return RollMode.NORMAL;
    }

    /** Flat modifier applied to the creature's saving throws (Bless +2, Bane −2). */
    public static int saveModifier(List<ActiveCondition> conds) {
        return rollAdjust(conds);
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private static int rollAdjust(List<ActiveCondition> conds) {
        int adj = 0;
        if (has(conds, BLESSED)) adj += 2;
        if (has(conds, BANED)) adj -= 2;
        return adj;
    }

    public static boolean has(List<ActiveCondition> conds, String name) {
        if (conds == null) {
            return false;
        }
        for (ActiveCondition c : conds) {
            if (c != null && name.equalsIgnoreCase(c.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyOf(List<ActiveCondition> conds, Set<String> names) {
        if (conds == null) {
            return false;
        }
        for (ActiveCondition c : conds) {
            if (c != null && c.name() != null && names.contains(c.name().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
