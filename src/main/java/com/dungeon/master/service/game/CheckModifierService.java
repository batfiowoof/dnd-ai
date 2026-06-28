package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

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
        int score = 10;
        try {
            PlayerRuntimeStateDto st = playerStateService.getState(player.getId());
            if (st.abilities() != null && ability != null) {
                score = st.abilities().getOrDefault(ability.toUpperCase(Locale.ROOT), 10);
            }
        } catch (RuntimeException e) {
            // No runtime state — default ability score.
        }
        int mod = Math.floorDiv(score - 10, 2);

        if (player.getCharacterId() != null && skill != null && !skill.isBlank()) {
            Character c = characterRepository.findById(player.getCharacterId()).orElse(null);
            if (c != null && isProficient(c, skill)) {
                mod += c.getProficiencyBonus();
            }
        }
        return mod;
    }

    private boolean isProficient(Character c, String skill) {
        if (c.getProficiencies() == null) {
            return false;
        }
        return c.getProficiencies().stream()
                .anyMatch(p -> p != null && p.equalsIgnoreCase(skill.trim()));
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
