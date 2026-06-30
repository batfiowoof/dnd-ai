package com.dungeon.master.controller;

import com.dungeon.master.config.AuthUtils;
import com.dungeon.master.model.dto.CharacterCreateUpdateRequest;
import com.dungeon.master.model.dto.CharacterDto;
import com.dungeon.master.model.dto.CharacterLevelUpRequest;
import com.dungeon.master.service.game.CharacterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
@Slf4j
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping
    public ResponseEntity<List<CharacterDto>> getMyCharacters(@AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        return ResponseEntity.ok(characterService.getCharactersByOwner(username));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CharacterDto> getCharacter(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        return ResponseEntity.ok(characterService.getCharacter(id, username));
    }

    @PostMapping
    public ResponseEntity<CharacterDto> createCharacter(
            @Valid @RequestBody CharacterCreateUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        CharacterDto character = characterService.createCharacter(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(character);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CharacterDto> updateCharacter(
            @PathVariable UUID id,
            @Valid @RequestBody CharacterCreateUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        return ResponseEntity.ok(characterService.updateCharacter(id, request, username));
    }

    @PostMapping("/{id}/level-up")
    public ResponseEntity<CharacterDto> levelUp(
            @PathVariable UUID id,
            @Valid @RequestBody CharacterLevelUpRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        return ResponseEntity.ok(characterService.levelUp(id, request, username));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCharacter(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        characterService.deleteCharacter(id, username);
        return ResponseEntity.noContent().build();
    }
}
