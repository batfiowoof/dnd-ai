package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.Combatant;
import com.dungeon.master.model.dto.CombatStateDto;
import com.dungeon.master.model.dto.EnemyDto;
import com.dungeon.master.model.dto.HealthBand;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.repository.EnemyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Builds the {@link CombatStateDto} broadcast snapshot from a {@link CombatEncounter}. */
@Component
@RequiredArgsConstructor
public class CombatMapper {

    private final EnemyRepository enemyRepository;

    public CombatStateDto toStateDto(CombatEncounter enc) {
        List<EnemyDto> enemies = enemyRepository.findBySessionId(enc.getSessionId()).stream()
                .map(e -> new EnemyDto(e.getId(), e.getName(), e.getArmorClass(), e.isAlive(),
                        e.getConditions().stream().map(ActiveCondition::name).toList(),
                        HealthBand.of(e.getCurrentHp(), e.getMaxHp()), CombatMath.enemyReachFeet(e)))
                .toList();
        List<Combatant> order = enc.getInitiativeOrder();
        Combatant active = (enc.getStatus() == CombatStatus.ACTIVE
                && enc.getActiveIndex() < order.size())
                ? order.get(enc.getActiveIndex()) : null;
        return new CombatStateDto(
                enc.getId(), enc.getStatus(), enc.getRound(),
                enc.getActiveIndex(), active, order, enemies, enc.getGridState());
    }
}
