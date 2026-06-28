package com.dungeon.master.controller;

import com.dungeon.master.service.ai.SpellCatalog;
import com.dungeon.master.service.game.MonsterCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only combat reference data for the client: the spell catalog (so the in-combat
 * cast menu knows each spell's level / effect / target type) and the monster catalog (so
 * the host's encounter picker can offer the full SRD bestiary). Backed by the in-memory
 * {@link SpellCatalog} / {@link MonsterCatalog}.
 */
@RestController
@RequestMapping("/api/combat")
@RequiredArgsConstructor
public class CombatReferenceController {

    private final SpellCatalog spellCatalog;
    private final MonsterCatalog monsterCatalog;

    /** Every spell's combat metadata; the client filters to a player's known spells. */
    @GetMapping("/spells")
    public List<SpellCatalog.SpellSummary> spells() {
        return spellCatalog.summaries();
    }

    /** Available encounter monsters (key/name/cr/hp/ac), sorted by challenge rating. */
    @GetMapping("/monsters")
    public List<MonsterCatalog.MonsterSummary> monsters() {
        return monsterCatalog.summaries();
    }
}
