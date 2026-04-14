package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.CharacterCreateUpdateRequest;
import com.dungeon.master.model.dto.CharacterDto;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.repository.CharacterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterService {

    private final CharacterRepository characterRepository;

    public List<CharacterDto> getCharactersByOwner(String username) {
        return characterRepository.findByOwnerUsernameOrderByUpdatedAtDesc(username).stream()
                .map(this::toDto)
                .toList();
    }

    public CharacterDto getCharacter(UUID id, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
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
                .equipment(request.equipment() != null ? request.equipment() : List.of())
                .proficiencies(request.proficiencies() != null ? request.proficiencies() : List.of())
                .features(request.features() != null ? request.features() : List.of())
                .backstory(request.backstory())
                .build();

        character = characterRepository.save(character);
        log.info("Character created: id={}, name={}, owner={}", character.getId(), character.getName(), username);
        return toDto(character);
    }

    @Transactional
    public CharacterDto updateCharacter(UUID id, CharacterCreateUpdateRequest request, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));

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
        if (request.equipment() != null) character.setEquipment(request.equipment());
        if (request.proficiencies() != null) character.setProficiencies(request.proficiencies());
        if (request.features() != null) character.setFeatures(request.features());
        character.setBackstory(request.backstory());
        character.setUpdatedAt(LocalDateTime.now());

        character = characterRepository.save(character);
        log.info("Character updated: id={}, name={}, owner={}", character.getId(), character.getName(), username);
        return toDto(character);
    }

    @Transactional
    public void deleteCharacter(UUID id, String username) {
        Character character = characterRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
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
                c.getBackstory(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
