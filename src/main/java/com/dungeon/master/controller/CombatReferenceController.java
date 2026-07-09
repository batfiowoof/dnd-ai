package com.dungeon.master.controller;

import com.dungeon.master.service.ai.SpellCatalog;
import com.dungeon.master.service.game.MagicItemCatalog;
import com.dungeon.master.service.game.MonsterCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only combat reference data for the client: the spell catalog (so the in-combat
 * cast menu knows each spell's level / effect / target type) and the monster catalog (so
 * the host's encounter picker can offer the full SRD bestiary) plus the magic-item catalog (so
 * the client can badge inventory items as magic and show rarity/attunement). Backed by the
 * in-memory {@link SpellCatalog} / {@link MonsterCatalog} / {@link MagicItemCatalog}.
 */
@RestController
@RequestMapping("/api/combat")
@RequiredArgsConstructor
public class CombatReferenceController {

    private final SpellCatalog spellCatalog;
    private final MonsterCatalog monsterCatalog;
    private final MagicItemCatalog magicItemCatalog;

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

    /** Every magic item's metadata (rarity/slot/attunement); the client badges matching inventory. */
    @GetMapping("/magic-items")
    public List<MagicItemCatalog.MagicItemSummary> magicItems() {
        return magicItemCatalog.summaries();
    }
}
