package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.MonsterAttack;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.service.game.DiceService;
import com.dungeon.master.service.game.MonsterCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A session's homebrew stat block is instantiated into a combat-valid Enemy by its CUSTOM_ key. */
class EnemyFactoryCustomMonsterTest {

    private final MonsterCatalog emptyCatalog = new MonsterCatalog();   // never load() → empty
    private final DiceService dice = new DiceService();

    private MonsterTemplate ashWraith() {
        return new MonsterTemplate(
                "CUSTOM_ASH_WRAITH", "Ash Wraith", "Medium", "Undead", 2.0, 13, 22, "5d8", 30, 2,
                Map.of("STR", 8, "DEX", 14, "CON", 12, "INT", 10, "WIS", 10, "CHA", 10),
                List.of(new MonsterAttack("Claw", "MELEE", 4, 5, null, "2d6", "Necrotic")),
                null);
    }

    @Test
    void buildsCustomMonsterFromSessionOverlay() {
        UUID sessionId = UUID.randomUUID();
        Enemy e = EnemyFactory.buildEnemy(emptyCatalog, List.of(ashWraith()), dice, sessionId,
                "CUSTOM_ASH_WRAITH", 0, Difficulty.NORMAL);

        assertEquals("Ash Wraith", e.getName());
        assertEquals(13, e.getArmorClass());
        assertEquals(22, e.getMaxHp(), "NORMAL difficulty leaves base HP unscaled");
        assertEquals(22, e.getCurrentHp());
        assertFalse(e.getAttacks().isEmpty(), "the custom attack must carry into combat");
        assertEquals("Claw", e.getAttacks().get(0).name());
        assertTrue(e.isAlive());
    }

    @Test
    void customKeyMatchesCaseInsensitively() {
        Enemy e = EnemyFactory.buildEnemy(emptyCatalog, List.of(ashWraith()), dice, UUID.randomUUID(),
                "custom_ash_wraith", 0, Difficulty.NORMAL);
        assertEquals("Ash Wraith", e.getName());
    }
}
