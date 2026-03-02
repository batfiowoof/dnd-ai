package com.dungeon.master.controller;

import com.dungeon.master.model.dto.CharacterCreateRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.service.game.PlayerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
@Slf4j
public class CharacterController {

    private final PlayerService playerService;

    @PostMapping
    public ResponseEntity<PlayerDto> createOrUpdateCharacter(
            @Valid @RequestBody CharacterCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getSubject();
        PlayerDto player = playerService.updateCharacter(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(player);
    }
}
