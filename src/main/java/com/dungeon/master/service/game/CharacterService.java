package com.dungeon.master.service.game;

import com.dungeon.master.exception.CharacterNotFoundException;
import com.dungeon.master.model.dto.AbilityScoreImprovement;
import com.dungeon.master.model.dto.CharacterCreateUpdateRequest;
import com.dungeon.master.model.dto.CharacterDto;
import com.dungeon.master.model.dto.CharacterLevelUpRequest;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final Dnd5eReferenceService referenceService;

    public List<CharacterDto> getCharactersByOwner(String username) {
        return characterRepository.findByOwnerUsernameOrderByUpdatedAtDesc(username).stream()
                .map(this::toDto)
                .toList();
    }

    public CharacterDto getCharacter(UUID id, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new CharacterNotFoundException("Character not found"));
        return toDto(character);
    }

    @Transactional
    public CharacterDto createCharacter(CharacterCreateUpdateRequest request, String username) {
        Character character = Character.builder()
                .ownerUsername(username)
                .name(request.name())
                .race(request.race())
                .characterClass(request.characterClass())
                .level(request.level())
                .background(request.background())
                .alignment(request.alignment())
                .strength(request.strength())
                .dexterity(request.dexterity())
                .constitution(request.constitution())
                .intelligence(request.intelligence())
                .wisdom(request.wisdom())
                .charisma(request.charisma())
                .hitPoints(request.hitPoints())
                .armorClass(request.armorClass())
                .speed(request.speed())
                // Proficiency bonus is derived from level, never trusted from the client.
                .proficiencyBonus(LevelingRules.proficiencyBonusForLevel(request.level()))
                .equipment(request.equipment() != null ? request.equipment() : List.of())
                .proficiencies(request.proficiencies() != null ? request.proficiencies() : List.of())
                .features(request.features() != null ? request.features() : List.of())
                .cantrips(request.cantrips() != null ? request.cantrips() : List.of())
                .knownSpells(request.knownSpells() != null ? request.knownSpells() : List.of())
                .startingInventory(request.startingInventory() != null ? request.startingInventory() : List.of())
                .backstory(request.backstory())
                .imageUrl(request.imageUrl())
                .build();

        character = characterRepository.save(character);
        log.info("Character created: id={}, name={}, owner={}", character.getId(), character.getName(), username);
        return toDto(character);
    }

    @Transactional
    public CharacterDto updateCharacter(UUID id, CharacterCreateUpdateRequest request, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new CharacterNotFoundException("Character not found"));

        character.setName(request.name());
        character.setRace(request.race());
        character.setCharacterClass(request.characterClass());
        character.setLevel(request.level());
        character.setBackground(request.background());
        character.setAlignment(request.alignment());
        character.setStrength(request.strength());
        character.setDexterity(request.dexterity());
        character.setConstitution(request.constitution());
        character.setIntelligence(request.intelligence());
        character.setWisdom(request.wisdom());
        character.setCharisma(request.charisma());
        character.setHitPoints(request.hitPoints());
        character.setArmorClass(request.armorClass());
        character.setSpeed(request.speed());
        // Keep the stored proficiency bonus consistent with whatever level was set.
        character.setProficiencyBonus(LevelingRules.proficiencyBonusForLevel(request.level()));
        if (request.equipment() != null) character.setEquipment(request.equipment());
        if (request.proficiencies() != null) character.setProficiencies(request.proficiencies());
        if (request.features() != null) character.setFeatures(request.features());
        if (request.cantrips() != null) character.setCantrips(request.cantrips());
        if (request.knownSpells() != null) character.setKnownSpells(request.knownSpells());
        if (request.startingInventory() != null) character.setStartingInventory(request.startingInventory());
        character.setBackstory(request.backstory());
        character.setImageUrl(request.imageUrl());
        character.setUpdatedAt(LocalDateTime.now());

        character = characterRepository.save(character);
        log.info("Character updated: id={}, name={}, owner={}", character.getId(), character.getName(), username);
        return toDto(character);
    }

    /**
     * Advance a character one level, applying the 5E mechanical progression: an Ability Score
     * Improvement (at ASI levels only), a fixed hit-point gain, a recalculated proficiency bonus,
     * and any newly chosen spells. Spell <em>slots</em> need no work here — they are reseeded from
     * the character's level on each session join.
     *
     * <p>Simplification: raising Constitution via an ASI affects this and future levels' hit points
     * but is not applied retroactively to prior levels (the strict SRD rule).
     */
    @Transactional
    public CharacterDto levelUp(UUID id, CharacterLevelUpRequest request, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new CharacterNotFoundException("Character not found"));

        if (character.getLevel() >= LevelingRules.MAX_LEVEL) {
            throw new IllegalStateException(
                    character.getName() + " is already at the maximum level of " + LevelingRules.MAX_LEVEL + ".");
        }

        int newLevel = character.getLevel() + 1;

        // Ability Score Improvement — only at the SRD ASI levels. CON is read afterwards for HP.
        if (LevelingRules.isAsiLevel(newLevel)) {
            applyAbilityScoreImprovement(character, request.asi());
        }

        int hitDie = hitDieForClass(character.getCharacterClass());
        int hpGain = Math.max(1, LevelingRules.fixedHpForHitDie(hitDie)
                + LevelingRules.abilityMod(character.getConstitution()));
        character.setHitPoints(character.getHitPoints() + hpGain);

        character.setLevel(newLevel);
        character.setProficiencyBonus(LevelingRules.proficiencyBonusForLevel(newLevel));

        // Append any newly chosen spells (casters), de-duplicated against the existing lists.
        character.setCantrips(appendUnique(character.getCantrips(), request.newCantrips()));
        character.setKnownSpells(appendUnique(character.getKnownSpells(), request.newSpells()));

        character.setUpdatedAt(LocalDateTime.now());
        character = characterRepository.save(character);
        log.info("Character leveled up: id={}, name={}, level={}, owner={}",
                character.getId(), character.getName(), newLevel, username);
        return toDto(character);
    }

    /** Hit-die size for a class from the SRD corpus (defaults to d8 when the class is unknown). */
    private int hitDieForClass(String characterClass) {
        String index = characterClass == null ? "" : characterClass.toLowerCase(Locale.ROOT);
        return referenceService.getClass(index)
                .map(rec -> rec.get("hitDie"))
                .filter(Number.class::isInstance)
                .map(v -> ((Number) v).intValue())
                .orElse(8);
    }

    private void applyAbilityScoreImprovement(Character character, AbilityScoreImprovement asi) {
        if (asi == null) {
            throw new IllegalArgumentException("An ability score improvement must be chosen at this level.");
        }
        if (asi.mode() == AbilityScoreImprovement.AsiMode.PLUS_TWO) {
            bumpAbility(character, asi.first(), 2);
        } else {
            if (asi.second() == null || asi.second().isBlank()) {
                throw new IllegalArgumentException("Choose two abilities to raise, or pick the +2 option.");
            }
            if (asi.first().equalsIgnoreCase(asi.second())) {
                throw new IllegalArgumentException("The two ability increases must target different abilities.");
            }
            bumpAbility(character, asi.first(), 1);
            bumpAbility(character, asi.second(), 1);
        }
    }

    private void bumpAbility(Character character, String ability, int amount) {
        String key = ability == null ? "" : ability.trim().toLowerCase(Locale.ROOT);
        int current = switch (key) {
            case "strength" -> character.getStrength();
            case "dexterity" -> character.getDexterity();
            case "constitution" -> character.getConstitution();
            case "intelligence" -> character.getIntelligence();
            case "wisdom" -> character.getWisdom();
            case "charisma" -> character.getCharisma();
            default -> throw new IllegalArgumentException("Unknown ability: " + ability);
        };
        int next = current + amount;
        if (next > LevelingRules.ASI_CAP) {
            throw new IllegalArgumentException("An ability score can't be raised above " + LevelingRules.ASI_CAP + ".");
        }
        switch (key) {
            case "strength" -> character.setStrength(next);
            case "dexterity" -> character.setDexterity(next);
            case "constitution" -> character.setConstitution(next);
            case "intelligence" -> character.setIntelligence(next);
            case "wisdom" -> character.setWisdom(next);
            case "charisma" -> character.setCharisma(next);
            default -> throw new IllegalArgumentException("Unknown ability: " + ability);
        }
    }

    /** Append new names to an existing list, skipping blanks and case-insensitive duplicates. */
    private static List<String> appendUnique(List<String> existing, List<String> additions) {
        List<String> result = new ArrayList<>(existing == null ? List.of() : existing);
        if (additions != null) {
            for (String name : additions) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String trimmed = name.trim();
                if (result.stream().noneMatch(e -> e.equalsIgnoreCase(trimmed))) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * Advance a character one level with only the AUTOMATIC parts applied (level, HP, proficiency).
     * This is the milestone path: choices can't be collected during an AI turn, so when the new level
     * grants an ASI or a caster's spell pick, the level is recorded in {@code pendingChoiceLevels} for
     * the player to finish later via {@link #applyPendingChoices}. A maxed character is left untouched.
     * Returns the saved character. Spell <em>slots</em> are refreshed on the runtime state, not here.
     */
    @Transactional
    public Character applyMilestoneLevel(Character character) {
        if (character.getLevel() >= LevelingRules.MAX_LEVEL) {
            return character;
        }
        int newLevel = character.getLevel() + 1;
        int hitDie = hitDieForClass(character.getCharacterClass());
        int hpGain = Math.max(1, LevelingRules.fixedHpForHitDie(hitDie)
                + LevelingRules.abilityMod(character.getConstitution()));
        character.setHitPoints(character.getHitPoints() + hpGain);
        character.setLevel(newLevel);
        character.setProficiencyBonus(LevelingRules.proficiencyBonusForLevel(newLevel));
        if (grantsChoice(character.getCharacterClass(), newLevel)) {
            List<Integer> pending = new ArrayList<>(character.getPendingChoiceLevels());
            if (!pending.contains(newLevel)) {
                pending.add(newLevel);
            }
            character.setPendingChoiceLevels(pending);
        }
        character.setUpdatedAt(LocalDateTime.now());
        return characterRepository.save(character);
    }

    /**
     * Resolve the earliest level whose choices are still owed: apply the chosen ASI (only at an ASI
     * level) and append any new spells, then clear that level from {@code pendingChoiceLevels}. Does
     * not change the character's level or HP — those already applied when the milestone fired. The
     * updated ability/spell snapshot reaches a live session on the next join (mirrors the manual
     * out-of-session flow).
     */
    @Transactional
    public CharacterDto applyPendingChoices(UUID id, CharacterLevelUpRequest request, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new CharacterNotFoundException("Character not found"));

        List<Integer> pending = new ArrayList<>(character.getPendingChoiceLevels());
        if (pending.isEmpty()) {
            throw new IllegalStateException(character.getName() + " has no pending level-up choices.");
        }
        int level = pending.stream().min(Integer::compareTo).orElseThrow();

        if (LevelingRules.isAsiLevel(level)) {
            applyAbilityScoreImprovement(character, request.asi());
        }
        character.setCantrips(appendUnique(character.getCantrips(), request.newCantrips()));
        character.setKnownSpells(appendUnique(character.getKnownSpells(), request.newSpells()));

        pending.remove(Integer.valueOf(level));
        character.setPendingChoiceLevels(pending);
        character.setUpdatedAt(LocalDateTime.now());
        character = characterRepository.save(character);
        log.info("Pending level-{} choices resolved: id={}, owner={}", level, character.getId(), username);
        return toDto(character);
    }

    /** Lower-cased class names that gain a spell pick on every level in our simplified model. */
    private static final Set<String> CASTER_CLASSES =
            Set.of("wizard", "sorcerer", "cleric", "druid", "bard", "warlock", "paladin", "ranger");

    /** A level grants a player choice when it's an ASI level or the class learns a new spell. */
    private static boolean grantsChoice(String characterClass, int newLevel) {
        if (LevelingRules.isAsiLevel(newLevel)) {
            return true;
        }
        return characterClass != null
                && CASTER_CLASSES.contains(characterClass.toLowerCase(Locale.ROOT));
    }

    @Transactional
    public void deleteCharacter(UUID id, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new CharacterNotFoundException("Character not found"));
        characterRepository.delete(character);
        log.info("Character deleted: id={}, owner={}", id, username);
    }

    public CharacterDto toDto(Character c) {
        return new CharacterDto(
                c.getId(),
                c.getName(),
                c.getRace(),
                c.getCharacterClass(),
                c.getLevel(),
                c.getBackground(),
                c.getAlignment(),
                c.getStrength(),
                c.getDexterity(),
                c.getConstitution(),
                c.getIntelligence(),
                c.getWisdom(),
                c.getCharisma(),
                c.getHitPoints(),
                c.getArmorClass(),
                c.getSpeed(),
                c.getProficiencyBonus(),
                c.getEquipment(),
                c.getProficiencies(),
                c.getFeatures(),
                c.getCantrips(),
                c.getKnownSpells(),
                c.getStartingInventory(),
                c.getPendingChoiceLevels(),
                c.getBackstory(),
                c.getImageUrl(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
