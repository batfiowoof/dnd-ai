package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.CombatActionEvent;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.RollMode;
import com.dungeon.master.model.enums.SpellEffectType;
import com.dungeon.master.model.enums.SpellResolution;
import com.dungeon.master.model.enums.SpellTargetType;
import com.dungeon.master.repository.EnemyRepository;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.GridService;
import com.dungeon.master.service.game.PlayerStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A legendary creature turns a failed saving throw into a success — but only against a spell that
 * would impose a condition, so it never wastes a charge soaking pure damage.
 */
class LegendaryResistanceTest {

    private final EnemyRepository enemyRepo = Mockito.mock(EnemyRepository.class);
    private final DiceService diceService = Mockito.mock(DiceService.class);
    private CombatSpellResolver resolver;

    private final UUID sessionId = UUID.randomUUID();
    private Enemy dragon;

    @BeforeEach
    void setUp() {
        resolver = new CombatSpellResolver(
                Mockito.mock(PlayerStateService.class), enemyRepo, diceService,
                Mockito.mock(GridService.class), Mockito.mock(CombatBroadcaster.class),
                Mockito.mock(CombatLookups.class));

        Map<String, Integer> abilities = new LinkedHashMap<>(Map.of("WIS", 10, "DEX", 10, "CON", 10));
        dragon = Enemy.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).name("Ancient Red Dragon")
                .maxHp(507).currentHp(507).armorClass(22).attackBonus(17).damageDice("2d8+10")
                .abilities(abilities).conditions(new ArrayList<>())
                .legendaryResistances(3).alive(true)
                .build();
        when(enemyRepo.findBySessionId(sessionId)).thenReturn(List.of(dragon));
        // Whatever it rolls, a 1 fails any DC worth casting.
        when(diceService.roll(anyString(), any(RollMode.class)))
                .thenReturn(new DiceRollResult("1d20", 1, 20, 0, RollMode.NORMAL,
                        List.of(1), null, 1, false, true));
    }

    /** A CONTROL spell (Hold Monster): the failed save is what a boss spends its charges on. */
    private static SpellEffect holdMonster() {
        return effect("Hold Monster", SpellEffectType.CONTROL, "paralyzed");
    }

    /** A pure damage save (Fireball): no condition, so no charge should burn. */
    private static SpellEffect fireball() {
        return effect("Fireball", SpellEffectType.DEBUFF, null);
    }

    private static SpellEffect effect(String name, SpellEffectType type, String condition) {
        return new SpellEffect(name, 3, type, SpellTargetType.ENEMY, SpellResolution.SAVE,
                "WIS", null, null, null, false, false, null, null, null, 0, null, 1,
                condition, false, true, "Action", "60 feet", null, null, false);
    }

    private List<String> cast(SpellEffect effect) {
        CombatEncounter enc = CombatEncounter.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).round(1).build();
        Player caster = new Player();
        caster.setId(UUID.randomUUID());
        caster.setCharacterName("Aria");

        List<CombatActionEvent.Target> results = new ArrayList<>();
        List<String> beat = new ArrayList<>();
        // A null character gives the default spell save DC of 10 — the dragon's flat 1 still fails.
        resolver.resolveSpellEffect(enc, sessionId, caster, null, effect,
                List.of(dragon.getId()), results, beat);
        return beat;
    }

    @Test
    void aBossBurnsACharToShrugOffASaveOrLoseSpell() {
        List<String> beat = cast(holdMonster());

        assertEquals(2, dragon.getLegendaryResistances(), "one charge should be spent");
        assertTrue(dragon.getConditions().isEmpty(),
                "the failed save was converted to a success — no paralysis");
        assertTrue(beat.stream().anyMatch(b -> b.contains("Legendary Resistance")),
                "the party should be told why the spell fizzled, got: " + beat);
    }

    @Test
    void chargesAreNotWastedSoakingPureDamage() {
        cast(fireball());

        assertEquals(3, dragon.getLegendaryResistances(),
                "a spell that imposes no condition should never burn a charge");
    }

    @Test
    void anExhaustedBossFinallySuccumbs() {
        dragon.setLegendaryResistances(1);

        cast(holdMonster());   // spends the last charge
        assertEquals(0, dragon.getLegendaryResistances());
        assertTrue(dragon.getConditions().isEmpty());

        cast(holdMonster());   // nothing left to spend
        assertEquals(0, dragon.getLegendaryResistances());
        assertFalse(dragon.getConditions().isEmpty(), "the dragon should now be paralyzed");
        assertEquals("paralyzed", dragon.getConditions().get(0).name());
    }

    @Test
    void anOrdinaryMonsterNeverResists() {
        dragon.setLegendaryResistances(0);

        cast(holdMonster());

        assertFalse(dragon.getConditions().isEmpty());
        assertEquals("paralyzed", dragon.getConditions().get(0).name());
    }
}
