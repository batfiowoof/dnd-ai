package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldRegion;
import com.dungeon.master.model.entity.World;
import com.dungeon.master.repository.WorldDocumentRepository;
import com.dungeon.master.service.ai.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Embeds a world's structured sections — each region, faction, NPC, and custom monster as its own
 * granular {@code LORE} document — tagged with the session that was started from it, so the DM stays
 * consistent via semantic retrieval as the campaign grows. Granular docs retrieve far better than one
 * blob. Fail-soft like {@code SrdSeeder}: if embeddings are unavailable, it logs and skips rather than
 * blocking session creation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorldRagIndexer {

    private final WorldDocumentRepository worldDocumentRepository;
    private final EmbeddingService embeddingService;

    /** Index every section of {@code world} as session-scoped LORE. Best-effort. */
    public void indexWorldForSession(UUID sessionId, World world) {
        if (world == null) {
            return;
        }
        try {
            if (world.getRegions() != null) {
                for (WorldRegion r : world.getRegions()) {
                    index(sessionId, "Region: " + r.name(),
                            join(r.name(), r.type(), r.description()));
                }
            }
            if (world.getFactions() != null) {
                for (WorldFaction f : world.getFactions()) {
                    index(sessionId, "Faction: " + f.name(),
                            join(f.name(), f.description(),
                                    "Goal: " + f.goal(), "Resource: " + f.resource(),
                                    "Pressure: " + f.pressure()));
                }
            }
            if (world.getNpcs() != null) {
                for (WorldNpc n : world.getNpcs()) {
                    index(sessionId, "NPC: " + n.name(),
                            join(n.name(), n.role(), n.race(), "At " + n.location(),
                                    "Bond: " + n.bond(), n.description()));
                }
            }
            if (world.getCustomMonsters() != null) {
                for (CustomMonster m : world.getCustomMonsters()) {
                    index(sessionId, "Creature: " + m.name(),
                            join(m.name(), m.type(), m.size()));
                }
            }
            log.info("Indexed world '{}' as session-scoped lore for session {}",
                    world.getName(), sessionId);
        } catch (Exception e) {
            // Best-effort: a missing/failed embedding model must not block starting a session.
            log.warn("Skipped world RAG indexing for session {} — {}", sessionId, e.getMessage());
        }
    }

    private void index(UUID sessionId, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        float[] embedding = embeddingService.generateEmbedding(content);
        worldDocumentRepository.insertWithSession(
                UUID.randomUUID(), title, content, "LORE", sessionId,
                embeddingService.embeddingToString(embedding));
    }

    /** Join non-blank parts with sentence separators into one embeddable chunk. */
    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank() && !endsWithLabel(p)) {
                if (sb.length() > 0) sb.append(". ");
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    /** Drop label-only fragments like "Goal: " left when the underlying value was blank. */
    private static boolean endsWithLabel(String p) {
        String t = p.trim();
        return t.endsWith(":") || t.equals("At") || t.endsWith(": ");
    }
}
