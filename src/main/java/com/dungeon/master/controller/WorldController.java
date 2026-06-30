package com.dungeon.master.controller;

import com.dungeon.master.config.AuthUtils;
import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.WorldCreateUpdateRequest;
import com.dungeon.master.model.dto.WorldDto;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldGenerateRequest;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldOverviewSuggestion;
import com.dungeon.master.model.dto.WorldRegion;
import com.dungeon.master.model.dto.WorldSummaryDto;
import com.dungeon.master.service.world.WorldBuilderAiService;
import com.dungeon.master.service.world.WorldService;
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

/**
 * REST surface for the World Builder. All endpoints are owner-scoped via the JWT subject.
 */
@RestController
@RequestMapping("/api/worlds")
@RequiredArgsConstructor
@Slf4j
public class WorldController {

    private final WorldService worldService;
    private final WorldBuilderAiService worldBuilderAiService;

    @GetMapping
    public ResponseEntity<List<WorldSummaryDto>> getMyWorlds(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(worldService.getWorldsByOwner(AuthUtils.username(jwt)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorldDto> getWorld(@PathVariable UUID id,
                                             @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(worldService.getWorld(id, AuthUtils.username(jwt)));
    }

    @PostMapping
    public ResponseEntity<WorldDto> createWorld(@Valid @RequestBody WorldCreateUpdateRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        WorldDto world = worldService.createWorld(request, AuthUtils.username(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(world);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorldDto> updateWorld(@PathVariable UUID id,
                                                @Valid @RequestBody WorldCreateUpdateRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(worldService.updateWorld(id, request, AuthUtils.username(jwt)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorld(@PathVariable UUID id,
                                            @AuthenticationPrincipal Jwt jwt) {
        worldService.deleteWorld(id, AuthUtils.username(jwt));
        return ResponseEntity.noContent().build();
    }

    /* ── Per-section AI generation ───────────────────────────────── */

    @PostMapping("/generate/overview")
    public ResponseEntity<WorldOverviewSuggestion> generateOverview(
            @RequestBody WorldGenerateRequest request) {
        return ResponseEntity.ok(worldBuilderAiService.generateOverview(request));
    }

    @PostMapping("/generate/regions")
    public ResponseEntity<List<WorldRegion>> generateRegions(
            @RequestBody WorldGenerateRequest request) {
        return ResponseEntity.ok(worldBuilderAiService.suggestRegions(request));
    }

    @PostMapping("/generate/factions")
    public ResponseEntity<List<WorldFaction>> generateFactions(
            @RequestBody WorldGenerateRequest request) {
        return ResponseEntity.ok(worldBuilderAiService.suggestFactions(request));
    }

    @PostMapping("/generate/npcs")
    public ResponseEntity<List<WorldNpc>> generateNpcs(
            @RequestBody WorldGenerateRequest request) {
        return ResponseEntity.ok(worldBuilderAiService.suggestNpcs(request));
    }

    @PostMapping("/generate/monster")
    public ResponseEntity<CustomMonster> generateMonster(
            @RequestBody WorldGenerateRequest request) {
        return ResponseEntity.ok(worldBuilderAiService.suggestMonster(request));
    }

    @PostMapping("/generate/milestones")
    public ResponseEntity<List<Milestone>> generateMilestones(
            @RequestBody WorldGenerateRequest request) {
        return ResponseEntity.ok(worldBuilderAiService.suggestMilestones(request));
    }
}
