package com.dungeon.master.service.game;

import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.enums.FeatKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the mechanical effects of a character's <em>origin feat</em> — the analogue of
 * {@code MagicItemEffects} for feats rather than gear. In the 2024 rules a background grants exactly
 * one origin feat, so the feat is derived from {@link Character#getBackground()} (via the SRD corpus
 * in {@link Dnd5eReferenceService}) rather than stored on its own column; there is no separate
 * ASI/level-4 feat selection in this app, so origin feats are the whole story.
 *
 * <p>Each accessor returns the numeric bonus / flag a caller folds in where the relevant math runs
 * (initiative in {@code CombatService}, max HP in the seed/level-up path, damage rerolls in combat,
 * luck points in {@code PlayerStateService}). Kept a thin resolver + arithmetic, no Spring state.
 */
@Component
@RequiredArgsConstructor
public class FeatEffects {

    private final Dnd5eReferenceService referenceService;

    /** The character's origin feat, resolved from its background; {@link FeatKey#OTHER} when unknown. */
    public FeatKey featOf(Character c) {
        if (c == null) {
            return FeatKey.OTHER;
        }
        return FeatKey.fromName(featNameForBackground(c.getBackground()));
    }

    public boolean has(Character c, FeatKey feat) {
        return featOf(c) == feat;
    }

    /** Alert (2024): add the character's Proficiency Bonus to Initiative rolls. */
    public int initiativeBonus(Character c) {
        return has(c, FeatKey.ALERT) ? proficiencyBonus(c) : 0;
    }

    /**
     * Tough: Hit Point maximum increases by 2 × character level. Applied as a derived bonus at HP
     * seed / level-up so it stays retroactive and never mutates the stored base {@code hitPoints}.
     */
    public int bonusMaxHp(Character c) {
        return has(c, FeatKey.TOUGH) ? 2 * Math.max(1, c == null ? 1 : c.getLevel()) : 0;
    }

    /** Savage Attacker: once per turn, roll a weapon's damage dice twice and use either. */
    public boolean hasSavageAttacker(Character c) {
        return has(c, FeatKey.SAVAGE_ATTACKER);
    }

    /** Lucky (2024): a pool of Luck Points equal to the Proficiency Bonus, else 0. */
    public int luckPoints(Character c) {
        return has(c, FeatKey.LUCKY) ? proficiencyBonus(c) : 0;
    }

    /* ── internals ───────────────────────────────────────────────────── */

    private static int proficiencyBonus(Character c) {
        return c == null ? 2 : c.getProficiencyBonus();
    }

    /**
     * The {@code feat} name a background grants, resolved by index or display name (case-insensitive).
     * {@code Character.background} may hold either the SRD index ("criminal") or the display name
     * ("Criminal"), so both are tried. Returns {@code null} when unresolved.
     */
    private String featNameForBackground(String background) {
        if (background == null || background.isBlank()) {
            return null;
        }
        String key = background.trim().toLowerCase(Locale.ROOT);
        Optional<Map<String, Object>> byIndex = referenceService.getBackground(key);
        Map<String, Object> record = byIndex.orElseGet(() ->
                referenceService.listBackgrounds().stream()
                        .filter(b -> key.equalsIgnoreCase(String.valueOf(b.get("name"))))
                        .findFirst()
                        .orElse(null));
        if (record == null) {
            return null;
        }
        Object feat = record.get("feat");
        return feat == null ? null : feat.toString();
    }
}
