package com.dungeon.master.controller;

import com.dungeon.master.model.dto.CreateSessionRequest;
import com.dungeon.master.model.dto.CreateSessionResponse;
import com.dungeon.master.model.dto.GameStateDto;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.dto.TurnEventDto;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.service.game.GameSessionService;
import com.dungeon.master.service.game.TurnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final TurnService turnService;
    private final PlayerRepository playerRepository;

    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getSubject();
        CreateSessionResponse response = gameSessionService.createSession(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{code}")
    public ResponseEntity<GameStateDto> getSessionByCode(@PathVariable String code) {
        GameSession session = gameSessionService.getSessionByCode(code);
        GameStateDto state = gameSessionService.getGameState(session.getId());
        return ResponseEntity.ok(state);
    }

    @PostMapping("/{sessionId}/join")
    public ResponseEntity<PlayerDto> joinSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody JoinSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getSubject();
        PlayerDto player = gameSessionService.joinSession(sessionId, request, username);
        return ResponseEntity.ok(player);
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<GameStateDto> startSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        gameSessionService.startSession(sessionId);
        GameStateDto state = gameSessionService.getGameState(sessionId);
        return ResponseEntity.ok(state);
    }

    @GetMapping("/{sessionId}/state")
    public ResponseEntity<GameStateDto> getGameState(@PathVariable UUID sessionId) {
        GameStateDto state = gameSessionService.getGameState(sessionId);
        return ResponseEntity.ok(state);
    }

    @GetMapping("/{sessionId}/history")
    public ResponseEntity<List<TurnEventDto>> getSessionHistory(@PathVariable UUID sessionId) {
        List<TurnEvent> events = turnService.getSessionHistory(sessionId);
        List<UUID> playerIds = events.stream()
                .map(TurnEvent::getPlayerId)
                .distinct()
                .toList();

        Map<UUID, Player> playersMap = playerRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        List<TurnEventDto> dtos = events.stream()
                .map(event -> {
                    Player player = playersMap.get(event.getPlayerId());
                    String playerName = player != null ? player.getCharacterName() : "Unknown";
                    return new TurnEventDto(
                            event.getId(),
                            event.getPlayerId(),
                            playerName,
                            event.getAction(),
                            event.getDmResponse(),
                            event.getTimestamp(),
                            event.getTurnNumber());
                })
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{sessionId}/players")
    public ResponseEntity<List<PlayerDto>> getPlayers(@PathVariable UUID sessionId) {
        List<PlayerDto> players = gameSessionService.getPlayers(sessionId);
        return ResponseEntity.ok(players);
    }
}
