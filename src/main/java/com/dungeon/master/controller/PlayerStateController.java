package com.dungeon.master.controller;

import com.dungeon.master.config.AuthUtils;
import com.dungeon.master.model.dto.AvailableShopsDto;
import com.dungeon.master.model.dto.CombatStateDto;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.service.game.CombatService;
import com.dungeon.master.service.game.PlayerService;
import com.dungeon.master.service.game.PlayerStateService;
import com.dungeon.master.service.game.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions/{sessionId}")
@RequiredArgsConstructor
@Slf4j
public class PlayerStateController {

    private final PlayerService playerService;
    private final PlayerStateService playerStateService;
    private final CombatService combatService;
    private final ShopService shopService;

    /** The calling player's own runtime state in this session. */
    @GetMapping("/me/state")
    public ResponseEntity<PlayerRuntimeStateDto> myState(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        PlayerDto player = playerService.getPlayerInSession(sessionId, username);
        return ResponseEntity.ok(playerStateService.getState(player.id()));
    }

    /** Runtime state for every player in the session (e.g. party HP bars). */
    @GetMapping("/states")
    public ResponseEntity<List<PlayerRuntimeStateDto>> sessionStates(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(playerStateService.getSessionStates(sessionId));
    }

    /** The calling player's purse and the shops open at the party's current location. */
    @GetMapping("/me/shops")
    public ResponseEntity<AvailableShopsDto> myShops(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        PlayerDto player = playerService.getPlayerInSession(sessionId, username);
        return ResponseEntity.ok(shopService.availableShops(sessionId, player.id()));
    }

    /** Active combat snapshot, or 204 if not currently in combat. */
    @GetMapping("/combat")
    public ResponseEntity<CombatStateDto> activeCombat(@PathVariable UUID sessionId) {
        return combatService.getActiveCombat(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
