package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.ProficiencyLevel;
import com.dungeon.master.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Ability-check modifier math, shared by the DM roll tools. Extracted from the old CheckService so
 * the authoritative roll path ({@link DmRollTools}) can compute the same modifier the engine always
 * used: ability modifier from runtime state, plus the character's proficiency bonus when the named
 * skill is one they are proficient in.
 */
@Service
@RequiredArgsConstructor
public class CheckModifierService {

    private final PlayerStateService playerStateService;
    private final CharacterRepository characterRepository;

    /**
     * Ability modifier from runtime state, plus the character's proficiency bonus when the named
     * skill is proficient. Falls back to ability mod only when skill/proficiency data is absent.
     */
    public int computeModifier(Player player, String ability, String skill) {
        int mod = abilityMod(player, ability);

        if (player.getCharacterId() != null && skill != null && !skill.isBlank()) {
            Character c = characterRepository.findById(player.getCharacterId()).orElse(null);
            if (c != null) {
                mod += proficiencyLevelFor(c, skill).bonus(c.getProficiencyBonus());
            }
        }
        return mod;
    }

    /**
     * Saving-throw modifier: the ability modifier, plus the full proficiency bonus when the character
     * is proficient in that ability's save (class saving-throw proficiencies). Ability-only when no
     * character or proficiency data is linked.
     */
    public int computeSaveModifier(Player player, String ability) {
        int mod = abilityMod(player, ability);
        if (player.getCharacterId() != null && ability != null) {
            Character c = characterRepository.findById(player.getCharacterId()).orElse(null);
            if (c != null && isSaveProficient(c, ability)) {
                mod += c.getProficiencyBonus();
            }
        }
        return mod;
    }

    /** Passive score for a skill = 10 + the character's check modifier for that skill (10 + mod, no roll). */
    public int passiveScore(Player player, String skill) {
        return 10 + computeModifier(player, Skills.abilityForSkill(skill), skill);
    }

    /** Ability modifier from runtime state (floor((score-10)/2)); defaults to +0 when state is absent. */
    private int abilityMod(Player player, String ability) {
        int score = 10;
        try {
            PlayerRuntimeStateDto st = playerStateService.getState(player.getId());
            if (st.abilities() != null && ability != null) {
                score = st.abilities().getOrDefault(Skills.abilityAbbrev(ability), 10);
            }
        } catch (RuntimeException e) {
            // No runtime state — default ability score.
        }
        return Math.floorDiv(score - 10, 2);
    }

    /**
     * Structured proficiency level for a skill. Prefers the {@code skillProficiencies} map; falls back
     * to the legacy flat {@code proficiencies} list (treated as PROFICIENT) for characters created
     * before the structured model existed. NONE when the skill isn't trained.
     */
    private ProficiencyLevel proficiencyLevelFor(Character c, String skill) {
        String key = skill.trim();
        if (c.getSkillProficiencies() != null && !c.getSkillProficiencies().isEmpty()) {
            for (Map.Entry<String, ProficiencyLevel> e : c.getSkillProficiencies().entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                    return e.getValue();
                }
            }
            return ProficiencyLevel.NONE;
        }
        if (c.getProficiencies() != null
                && c.getProficiencies().stream().anyMatch(p -> p != null && p.equalsIgnoreCase(key))) {
            return ProficiencyLevel.PROFICIENT;
        }
        return ProficiencyLevel.NONE;
    }

    private boolean isSaveProficient(Character c, String ability) {
        if (c.getSavingThrowProficiencies() == null) {
            return false;
        }
        String abbr = Skills.abilityAbbrev(ability);
        return c.getSavingThrowProficiencies().stream()
                .anyMatch(a -> a != null && a.equalsIgnoreCase(abbr));
    }

    /** "1d20+5" / "1d20-1" / "1d20" notation for a 1d20 check with the given modifier. */
    public String notation(int modifier) {
        return "1d20" + (modifier > 0 ? "+" + modifier : modifier < 0 ? String.valueOf(modifier) : "");
    }

    /** Human label for a check, e.g. "DEX (Acrobatics) check". */
    public String label(String ability, String skill) {
        String skillPart = (skill == null || skill.isBlank()) ? "" : " (" + skill + ")";
        return ability + skillPart + " check";
    }

    /** Human label for a saving throw, e.g. "DEX saving throw". */
    public String saveLabel(String ability) {
        return Skills.abilityAbbrev(ability) + " saving throw";
    }

    /** Fallback DC when the DM omits one — banded by session difficulty. */
    public static int defaultDc(Difficulty d) {
        return switch (d == null ? Difficulty.NORMAL : d) {
            case EASY -> 10;
            case NORMAL -> 13;
            case DEADLY -> 17;
        };
    }

    /** Fallback NPC modifier for a contest when the DM omits one — banded by difficulty. */
    public static int defaultContestMod(Difficulty d) {
        return switch (d == null ? Difficulty.NORMAL : d) {
            case EASY -> 2;
            case NORMAL -> 4;
            case DEADLY -> 6;
        };
    }
}
