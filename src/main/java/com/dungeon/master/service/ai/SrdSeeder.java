package com.dungeon.master.service.ai;

import com.dungeon.master.model.enums.DocumentCategory;
import com.dungeon.master.repository.WorldDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the bundled full SRD reference ({@link SrdContent}) into the {@code world_documents}
 * pgvector store as {@code RULES} documents, so the DM can semantically retrieve rules text for
 * grounded narration.
 *
 * <p>Runs once on startup and is <strong>idempotent by deterministic id</strong>: each entry's
 * primary key is derived from its stable {@code key} ({@link #idFor}), so re-runs skip entries that
 * already exist and a crash part-way through simply resumes on the next boot. (A title-based guard
 * would be unsafe at this scale — names collide across ~2,000 entries.) It is also
 * <strong>fail-soft</strong>: embedding goes through the host's Ollama, which may be unreachable
 * when the backend boots (especially in Docker) — any failure is logged and the remaining entries
 * are left for the next boot rather than crashing startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SrdSeeder {

    private static final int LOG_EVERY = 200;

    private final SrdContent srdContent;
    private final EmbeddingService embeddingService;
    private final WorldDocumentRepository worldDocumentRepository;

    @Value("${dnd.rag.seed-srd:true}")
    private boolean seedEnabled;

    /** Stable per-entry primary key derived from the SRD key (e.g. {@code SRD:SPELL:fireball}). */
    static UUID idFor(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!seedEnabled) {
            log.info("SRD seeding disabled (dnd.rag.seed-srd=false)");
            return;
        }
        if (srdContent.isEmpty()) {
            log.warn("No SRD reference content loaded — nothing to seed");
            return;
        }

        List<SrdContent.Entry> entries = srdContent.all();
        int seeded = 0;
        int skipped = 0;
        int processed = 0;
        for (SrdContent.Entry entry : entries) {
            processed++;
            UUID id = idFor(entry.key());
            try {
                if (worldDocumentRepository.existsById(id)) {
                    skipped++;
                    continue; // already persisted — idempotent + resumes a partial prior seed
                }
                float[] embedding = embeddingService.generateEmbedding(entry.content());
                worldDocumentRepository.insertWithEmbedding(
                        id,
                        entry.title(),
                        entry.content(),
                        DocumentCategory.RULES.name(),
                        embeddingService.embeddingToString(embedding));
                seeded++;
                if (seeded % LOG_EVERY == 0) {
                    log.info("SRD seeding… {} embedded ({} / {} processed)", seeded, processed, entries.size());
                }
            } catch (Exception e) {
                // Most likely Ollama is not yet reachable. Stop and let the next boot finish.
                log.warn("SRD seed interrupted at '{}' ({} embedded, {} of {} processed) — will resume next boot: {}",
                        entry.title(), seeded, processed, entries.size(), e.getMessage());
                return;
            }
        }

        if (seeded > 0) {
            log.info("SRD seed complete: {} new entries embedded, {} already present ({} total)",
                    seeded, skipped, entries.size());
        } else {
            log.info("SRD already seeded: all {} entries present", entries.size());
        }
    }
}
