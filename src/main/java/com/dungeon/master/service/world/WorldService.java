package com.dungeon.master.service.world;

import com.dungeon.master.exception.WorldNotFoundException;
import com.dungeon.master.model.dto.WorldCreateUpdateRequest;
import com.dungeon.master.model.dto.WorldDto;
import com.dungeon.master.model.dto.WorldSummaryDto;
import com.dungeon.master.model.entity.World;
import com.dungeon.master.model.mapper.WorldMapper;
import com.dungeon.master.repository.WorldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for player-authored {@link World}s. All reads and writes are scoped by owner, so a user can
 * only ever see or mutate their own worlds (a foreign id surfaces as {@link WorldNotFoundException} →
 * 404, never leaking existence). Every structured section is sanitized via {@link WorldSanitizer} on
 * write so persisted content is well-formed and custom monsters are combat-legal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorldService {

    private final WorldRepository worldRepository;
    private final WorldMapper worldMapper;
    private final WorldSanitizer sanitizer;

    public List<WorldSummaryDto> getWorldsByOwner(String username) {
        return worldRepository.findByOwnerUsernameOrderByUpdatedAtDesc(username).stream()
                .map(worldMapper::toSummary)
                .toList();
    }

    public WorldDto getWorld(UUID id, String username) {
        return worldMapper.toDto(requireOwned(id, username));
    }

    /** Load an owned world entity (used by session creation to compile it into a game). */
    public World requireOwned(UUID id, String username) {
        return worldRepository.findByIdAndOwnerUsername(id, username)
                .orElseThrow(() -> new WorldNotFoundException("World not found"));
    }

    @Transactional
    public WorldDto createWorld(WorldCreateUpdateRequest request, String username) {
        World world = World.builder()
                .ownerUsername(username)
                .build();
        applyRequest(world, request);
        world = worldRepository.save(world);
        log.info("World created: id={}, name={}, owner={}", world.getId(), world.getName(), username);
        return worldMapper.toDto(world);
    }

    @Transactional
    public WorldDto updateWorld(UUID id, WorldCreateUpdateRequest request, String username) {
        World world = requireOwned(id, username);
        applyRequest(world, request);
        world.setUpdatedAt(LocalDateTime.now());
        world = worldRepository.save(world);
        log.info("World updated: id={}, name={}, owner={}", world.getId(), world.getName(), username);
        return worldMapper.toDto(world);
    }

    @Transactional
    public void deleteWorld(UUID id, String username) {
        World world = requireOwned(id, username);
        worldRepository.delete(world);
        log.info("World deleted: id={}, owner={}", id, username);
    }

    /** Copy a sanitized request onto a world entity (shared by create + update). */
    private void applyRequest(World world, WorldCreateUpdateRequest request) {
        world.setName(request.name().trim());
        world.setTagline(trimOrNull(request.tagline()));
        world.setOverview(request.overview());
        world.setTone(trimOrNull(request.tone()));
        world.setMagicLevel(trimOrNull(request.magicLevel()));
        world.setRegions(sanitizer.cleanRegions(request.regions()));
        world.setFactions(sanitizer.cleanFactions(request.factions()));
        world.setNpcs(sanitizer.cleanNpcs(request.npcs()));
        world.setCustomMonsters(sanitizer.sanitizeMonsters(request.customMonsters()));
        world.setMilestones(sanitizer.normalizeMilestones(request.milestones()));
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
