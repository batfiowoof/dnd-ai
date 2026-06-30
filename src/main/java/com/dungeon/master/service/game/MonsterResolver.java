package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.MonsterTemplate;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves an encounter monster key for a session, overlaying that session's homebrew
 * {@link CustomMonster} stat blocks (copied from the {@link com.dungeon.master.model.entity.World}
 * it was started from) on top of the global SRD {@link MonsterCatalog}. Custom keys win on a clash.
 * This is the single seam that lets authored monsters be spawned and validated like SRD creatures.
 */
@Component
@RequiredArgsConstructor
public class MonsterResolver {

    private final MonsterCatalog monsterCatalog;
    private final GameSessionRepository sessionRepository;

    /** This session's custom monsters as engine {@link MonsterTemplate}s (empty when none). */
    public List<MonsterTemplate> customTemplates(UUID sessionId) {
        List<CustomMonster> custom = sessionRepository.findById(sessionId)
                .map(GameSession::getCustomMonsters)
                .orElse(List.of());
        List<MonsterTemplate> out = new ArrayList<>(custom.size());
        for (CustomMonster m : custom) {
            out.add(m.toTemplate());
        }
        return out;
    }

    /**
     * Every key the DM may legally name in an {@code [[ENCOUNTER:…]]} tag for this session: the SRD
     * catalogue (or the legacy {@link Bestiary} fallback when no catalogue loaded) plus this session's
     * custom monster keys.
     */
    public Set<String> validKeys(UUID sessionId) {
        Set<String> keys = new HashSet<>(
                monsterCatalog.isEmpty() ? Bestiary.keys() : monsterCatalog.keys());
        for (MonsterTemplate t : customTemplates(sessionId)) {
            keys.add(t.key());
        }
        return keys;
    }
}
