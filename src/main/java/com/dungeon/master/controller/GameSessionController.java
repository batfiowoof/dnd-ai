package com.dungeon.master.controller;

import com.dungeon.master.config.AuthUtils;
import com.dungeon.master.model.dto.CreateSessionRequest;
import com.dungeon.master.model.dto.CreateSessionResponse;
import com.dungeon.master.model.dto.GameStateDto;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.dto.SessionSummaryDto;
import com.dungeon.master.model.dto.TurnEventDto;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.service.game.GameSessionService;
import com.dungeon.master.service.game.SessionMembershipService;
import com.dungeon.master.service.game.TurnService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final SessionMembershipService sessionMembershipService;
    private final TurnService turnService;

    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        CreateSessionResponse response = gameSessionService.createSession(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my-sessions")
    public ResponseEntity<List<SessionSummaryDto>> getMySessions(
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        return ResponseEntity.ok(gameSessionService.getUserSessions(username));
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
        String username = AuthUtils.username(jwt);
        PlayerDto player = sessionMembershipService.joinSession(sessionId, request, username);
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
        return ResponseEntity.ok(turnService.getSessionHistoryDtos(sessionId));
    }

    @GetMapping("/{sessionId}/players")
    public ResponseEntity<List<PlayerDto>> getPlayers(@PathVariable UUID sessionId) {
        List<PlayerDto> players = gameSessionService.getPlayers(sessionId);
        return ResponseEntity.ok(players);
    }

    @DeleteMapping("/{sessionId}/players/{playerId}")
    public ResponseEntity<Void> removePlayer(
            @PathVariable UUID sessionId,
            @PathVariable UUID playerId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        sessionMembershipService.removePlayer(sessionId, playerId, username);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/leave")
    public ResponseEntity<Void> leaveSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        sessionMembershipService.leaveSession(sessionId, username);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        gameSessionService.deleteSession(sessionId, username);
        return ResponseEntity.noContent().build();
    }
}
