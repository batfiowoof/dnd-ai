package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.enums.RollMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the SRD condition mechanics engine. */
class ConditionRulesTest {

    private static List<ActiveCondition> conds(String... names) {
        return java.util.Arrays.stream(names).map(ActiveCondition::of).toList();
    }

    @Test
    void incapacitatingConditionsCostTheTurn() {
        assertThat(ConditionRules.incapacitated(conds("paralyzed"))).isTrue();
        assertThat(ConditionRules.incapacitated(conds("stunned"))).isTrue();
        assertThat(ConditionRules.incapacitated(conds("unconscious"))).isTrue();
        assertThat(ConditionRules.incapacitated(conds("restrained"))).isFalse();
        assertThat(ConditionRules.incapacitated(List.of())).isFalse();
    }

    @Test
    void restrainedAndSlowedAffectSpeed() {
        assertThat(ConditionRules.effectiveSpeed(30, conds("restrained"))).isZero();
        assertThat(ConditionRules.effectiveSpeed(30, conds("grappled"))).isZero();
        assertThat(ConditionRules.effectiveSpeed(30, conds("slowed"))).isEqualTo(15);
        assertThat(ConditionRules.effectiveSpeed(30, List.of())).isEqualTo(30);
    }

    @Test
    void attackerWithBlindedHasDisadvantage() {
        RollMode mode = ConditionRules.attackMode(conds("blinded"), List.of(), true);
        assertThat(mode).isEqualTo(RollMode.DISADVANTAGE);
    }

    @Test
    void attackingARestrainedTargetHasAdvantage() {
        RollMode mode = ConditionRules.attackMode(List.of(), conds("restrained"), true);
        assertThat(mode).isEqualTo(RollMode.ADVANTAGE);
    }

    @Test
    void faerieFireGrantsAttackersAdvantage() {
        RollMode mode = ConditionRules.attackMode(List.of(), conds("faerie-fire"), false);
        assertThat(mode).isEqualTo(RollMode.ADVANTAGE);
    }

    @Test
    void advantageAndDisadvantageCancelToNormal() {
        // Attacker is blinded (disadvantage) but the target is restrained (advantage) → cancel.
        RollMode mode = ConditionRules.attackMode(conds("blinded"), conds("restrained"), true);
        assertThat(mode).isEqualTo(RollMode.NORMAL);
    }

    @Test
    void proneIsMeleeAdvantageButRangedDisadvantage() {
        assertThat(ConditionRules.attackMode(List.of(), conds("prone"), true))
                .isEqualTo(RollMode.ADVANTAGE);
        assertThat(ConditionRules.attackMode(List.of(), conds("prone"), false))
                .isEqualTo(RollMode.DISADVANTAGE);
    }

    @Test
    void paralyzedAutoFailsStrengthAndDexterity() {
        assertThat(ConditionRules.autoFailsSave(conds("paralyzed"), "STR")).isTrue();
        assertThat(ConditionRules.autoFailsSave(conds("paralyzed"), "DEX")).isTrue();
        assertThat(ConditionRules.autoFailsSave(conds("paralyzed"), "WIS")).isFalse();
        assertThat(ConditionRules.autoFailsSave(conds("restrained"), "DEX")).isFalse();
    }

    @Test
    void restrainedGivesDexSaveDisadvantage() {
        assertThat(ConditionRules.saveMode(conds("restrained"), "DEX"))
                .isEqualTo(RollMode.DISADVANTAGE);
        assertThat(ConditionRules.saveMode(conds("restrained"), "STR"))
                .isEqualTo(RollMode.NORMAL);
    }

    @Test
    void meleeAutoCritOnParalyzedOrUnconscious() {
        assertThat(ConditionRules.autoCritMelee(conds("paralyzed"), true)).isTrue();
        assertThat(ConditionRules.autoCritMelee(conds("unconscious"), true)).isTrue();
        assertThat(ConditionRules.autoCritMelee(conds("paralyzed"), false)).isFalse();  // ranged
        assertThat(ConditionRules.autoCritMelee(conds("restrained"), true)).isFalse();
    }

    @Test
    void blessAndBaneAreFlatModifiers() {
        assertThat(ConditionRules.attackModifier(conds("blessed"))).isEqualTo(2);
        assertThat(ConditionRules.attackModifier(conds("baned"))).isEqualTo(-2);
        assertThat(ConditionRules.saveModifier(conds("blessed", "baned"))).isZero();
    }
}
