package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.NpcStateDto;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.entity.NpcState;
import com.dungeon.master.model.entity.World;
import com.dungeon.master.model.enums.DispositionBand;
import com.dungeon.master.repository.NpcStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the per-session NPC disposition (how each NPC feels about the party). Seeded from the authored
 * {@code WorldNpc.disposition} baseline at session creation and nudged by the AI DM during play via
 * {@link #adjust}. NPCs are keyed by canonical name (trim + lower-case), the same idiom used for
 * regions and milestone keys. The band is always derived from the score — never stored.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NpcStateService {

    private final NpcStateRepository npcStateRepository;

    /** Result of a disposition adjustment, returned to the AI DM tool so it can narrate the shift. */
    public record AdjustResult(boolean found, String name, int disposition, String band, boolean changed) {
        static AdjustResult notFound(String name) {
            return new AdjustResult(false, name, 0, "", false);
        }
    }

    /** Seed one disposition row per authored NPC (deduplicated by canonical name). Best-effort. */
    @Transactional
    public void seedForSession(UUID sessionId, World world) {
        if (world == null || world.getNpcs() == null || world.getNpcs().isEmpty()) {
            return;
        }
        Map<String, WorldNpc> byCanonical = new LinkedHashMap<>();
        for (WorldNpc n : world.getNpcs()) {
            if (n == null || n.name() == null || n.name().isBlank()) {
                continue;
            }
            byCanonical.putIfAbsent(canonical(n.name()), n);
        }
        List<NpcState> rows = new ArrayList<>(byCanonical.size());
        for (WorldNpc n : byCanonical.values()) {
            int baseline = DispositionBand.clamp(n.disposition() == null ? 0 : n.disposition());
            rows.add(NpcState.builder()
                    .sessionId(sessionId)
                    .npcName(n.name().trim())
                    .disposition(baseline)
                    .baseline(baseline)
                    .build());
        }
        npcStateRepository.saveAll(rows);
        log.info("Seeded {} NPC dispositions for session {}", rows.size(), sessionId);
    }

    /** Nudge an NPC's disposition by {@code delta} (clamped). Returns the outcome for the DM to narrate. */
    @Transactional
    public AdjustResult adjust(UUID sessionId, String npcName, int delta) {
        if (npcName == null || npcName.isBlank()) {
            return AdjustResult.notFound(npcName);
        }
        NpcState state = npcStateRepository
                .findBySessionIdAndNpcNameIgnoreCase(sessionId, npcName.trim())
                .orElse(null);
        if (state == null) {
            return AdjustResult.notFound(npcName);
        }
        int before = state.getDisposition();
        int after = DispositionBand.clamp(before + delta);
        state.setDisposition(after);
        npcStateRepository.save(state);
        log.info("Adjusted NPC disposition: session={}, npc={}, {}->{}", sessionId, state.getNpcName(),
                before, after);
        return new AdjustResult(true, state.getNpcName(), after,
                DispositionBand.fromScore(after).label(), after != before);
    }

    /** All NPC dispositions for the session, as read-model DTOs. */
    public List<NpcStateDto> getStates(UUID sessionId) {
        return npcStateRepository.findBySessionId(sessionId).stream()
                .map(s -> new NpcStateDto(s.getNpcName(), s.getDisposition(),
                        DispositionBand.fromScore(s.getDisposition()).label()))
                .toList();
    }

    private static String canonical(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
