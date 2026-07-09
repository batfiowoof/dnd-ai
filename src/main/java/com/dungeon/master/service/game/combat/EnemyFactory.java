package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.MonsterAction;
import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.service.game.Bestiary;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.MonsterCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds {@link Enemy} entities for a new encounter, scaling HP and attack bonus to the
 * session difficulty. Prefers a {@link MonsterCatalog} stat block, falling back to the
 * legacy hardcoded {@link Bestiary}. Stateless: the {@link MonsterCatalog} and
 * {@link DiceService} are passed in so this needs no Spring wiring.
 */
public final class EnemyFactory {

    private EnemyFactory() {}

    /**
     * Build one enemy, resolving {@code key} against the session's homebrew {@code sessionCustom}
     * stat blocks first (copied from the world it was started from), then the {@link MonsterCatalog},
     * and finally the legacy hardcoded {@link Bestiary}. {@code index} numbers duplicates ("Goblin 2");
     * 0 means it is the only one of its type.
     *
     * <p>{@code inLair} carries the host's "In its lair" choice: only then are the monster's lair
     * actions copied onto the enemy, so a non-empty {@link Enemy#getLairActions()} means "this fight
     * happens in this creature's lair".
     */
    public static Enemy buildEnemy(MonsterCatalog monsterCatalog, List<MonsterTemplate> sessionCustom,
                                   DiceService diceService, UUID sessionId, String key, int index,
                                   Difficulty difficulty, boolean inLair) {
        Optional<MonsterTemplate> tmpl = findCustom(sessionCustom, key)
                .or(() -> monsterCatalog.get(key));
        if (tmpl.isPresent()) {
            return fromTemplate(tmpl.get(), diceService, sessionId, key, index, difficulty, inLair);
        }
        return fromBestiary(diceService, sessionId, key, index, difficulty);
    }

    /** Overload for encounters that are not fought in a lair. */
    public static Enemy buildEnemy(MonsterCatalog monsterCatalog, List<MonsterTemplate> sessionCustom,
                                   DiceService diceService, UUID sessionId, String key, int index,
                                   Difficulty difficulty) {
        return buildEnemy(monsterCatalog, sessionCustom, diceService, sessionId, key, index,
                difficulty, false);
    }

    /** Backward-compatible overload with no session overlay (used by tests / catalog-only callers). */
    public static Enemy buildEnemy(MonsterCatalog monsterCatalog, DiceService diceService,
                                   UUID sessionId, String key, int index, Difficulty difficulty) {
        return buildEnemy(monsterCatalog, List.of(), diceService, sessionId, key, index, difficulty,
                false);
    }

    private static Optional<MonsterTemplate> findCustom(List<MonsterTemplate> sessionCustom, String key) {
        if (sessionCustom == null || key == null) {
            return Optional.empty();
        }
        return sessionCustom.stream().filter(t -> key.equalsIgnoreCase(t.key())).findFirst();
    }

    private static Enemy fromTemplate(MonsterTemplate t, DiceService diceService, UUID sessionId,
                                      String key, int index, Difficulty difficulty, boolean inLair) {
        int atkDelta = attackBonusDelta(difficulty);
        int d20 = diceService.roll("1d20").total();
        String name = index > 0 ? t.name() + " " + index : t.name();
        int hp = scaleHp(t.hp(), difficulty);
        List<MonsterAttack> scaled = new ArrayList<>();
        for (MonsterAttack a : t.attacks()) {
            scaled.add(new MonsterAttack(a.name(), a.kind(), a.toHit() + atkDelta,
                    a.reach(), a.range(), a.damageDice(), a.damageType()));
        }
        MonsterAttack primary = scaled.isEmpty() ? null : scaled.get(0);
        int perTurn = t.multiattack() != null ? Math.max(1, t.multiattack().count()) : 1;
        // Legendary actions ride along by value; lair actions only when fought in the lair, so a
        // non-empty lairActions on the Enemy is the engine's "this fight has a lair" marker.
        List<MonsterAction> legendary = new ArrayList<>(t.legendaryActions());
        List<MonsterAction> lair = inLair ? new ArrayList<>(t.lairActions()) : new ArrayList<>();
        return Enemy.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).name(name)
                .maxHp(hp).currentHp(hp).armorClass(t.ac())
                .attackBonus(primary != null ? primary.toHit() : 2 + atkDelta)
                .damageDice(primary != null ? primary.damageDice() : "1d6")
                .attacks(scaled).attacksPerTurn(perTurn)
                .abilities(t.abilities() != null ? new LinkedHashMap<>(t.abilities())
                        : new LinkedHashMap<>())
                .speed(t.speed() != null ? t.speed() : 30)
                .initiative(d20 + t.dexMod()).dexMod(t.dexMod()).alive(true)
                .legendaryActions(legendary).lairActions(lair)
                .legendaryActionMax(t.legendaryActionMax())
                // Seed a full budget so the boss can act before its own first turn refills it.
                .legendaryActionsRemaining(t.legendaryActionMax())
                .legendaryResistances(t.legendaryResistances())
                .build();
    }

    private static Enemy fromBestiary(DiceService diceService, UUID sessionId, String key, int index,
                                      Difficulty difficulty) {
        int atkDelta = attackBonusDelta(difficulty);
        int d20 = diceService.roll("1d20").total();
        Bestiary.Template t = Bestiary.get(key);
        String name = index > 0 ? t.name() + " " + index : t.name();
        int hp = scaleHp(t.hp(), difficulty);
        return Enemy.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).name(name)
                .maxHp(hp).currentHp(hp).armorClass(t.armorClass())
                .attackBonus(t.attackBonus() + atkDelta).damageDice(t.damageDice())
                .attacksPerTurn(1)
                .initiative(d20 + t.dexMod()).dexMod(t.dexMod()).alive(true)
                .build();
    }

    /** Scale an enemy's base HP by difficulty (EASY 0.75×, NORMAL 1×, DEADLY 1.4×), min 1. */
    public static int scaleHp(int baseHp, Difficulty difficulty) {
        double factor = switch (difficulty) {
            case EASY -> 0.75;
            case NORMAL -> 1.0;
            case DEADLY -> 1.4;
        };
        return Math.max(1, (int) Math.round(baseHp * factor));
    }

    /** Difficulty adjustment to enemy attack bonus (EASY −1, NORMAL 0, DEADLY +2). */
    public static int attackBonusDelta(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> -1;
            case NORMAL -> 0;
            case DEADLY -> 2;
        };
    }
}
