package com.dungeon.master.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the bundled SRD 5.2.1 reference corpus ({@code resources/srd/srd-5.2.1.json}) into memory
 * once at startup. The file is a flat JSON array of pre-built {@link Entry} records produced by
 * {@code tools/extract-srd.py} from the official SRD 5.2.1 PDF (CC-BY-4.0). It serves two consumers:
 * {@link SrdSeeder} embeds every entry into the pgvector store for <em>semantic</em> retrieval,
 * while the combat path uses {@link #monsterEntryByName(String)} for <em>direct</em>, name-based
 * injection (the engine already knows which monsters are in an encounter, so similarity search
 * isn't needed there).
 *
 * <p><strong>Monster entries deliberately omit numeric combat stats</strong> (AC / HP / to-hit /
 * damage / CR / ability scores) so the lore can never contradict the engine's authoritative
 * {@code Bestiary} math — the extractor strips those at build time.
 *
 * <p>See {@code NOTICE} — content derived from the SRD 5.2.1 under CC-BY-4.0.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SrdContent {

    /** One reference entry. {@code key} is stable + unique ({@code SRD:<TYPE>:<slug>}). */
    public record Entry(String key, String title, String type, String content) {}

    /** Single bundled corpus file (pre-built array of {@code {key,title,type,content}}). */
    private static final String RESOURCE = "srd/srd-5.2.1.json";

    // Self-instantiated (not injected): under Spring Boot 4 the auto-configured bean is the
    // Jackson 3 ObjectMapper, so injecting this Jackson 2 type finds no bean. A private instance
    // is fine — it only reads a bundled classpath resource once at startup.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Insertion-ordered, keyed by the entry's uppercased {@code key}. */
    private final Map<String, Entry> byKey = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode arr = objectMapper.readTree(in);
            if (arr == null || !arr.isArray()) {
                log.warn("SRD corpus {} is not a JSON array — no reference content loaded", RESOURCE);
                return;
            }
            for (JsonNode node : arr) {
                String key = text(node, "key");
                String title = text(node, "title");
                String type = text(node, "type");
                String content = text(node, "content");
                if (key.isBlank() || title.isBlank() || content.isBlank()) {
                    continue;
                }
                byKey.put(key.toUpperCase(Locale.ROOT), new Entry(key, title, type, content));
            }
            log.info("Loaded {} SRD 5.2.1 reference entries from {}", byKey.size(), RESOURCE);
        } catch (Exception e) {
            log.warn("Failed to load SRD corpus {} — no reference content loaded: {}",
                    RESOURCE, e.getMessage());
        }
    }

    /* ── public API ──────────────────────────────────────────────── */

    /** Every loaded entry (used by the seeder to embed + persist). */
    public List<Entry> all() {
        return List.copyOf(byKey.values());
    }

    public Optional<Entry> byKey(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(byKey.get(key.toUpperCase(Locale.ROOT)));
    }

    /**
     * Behavioural lore for an encounter enemy's display name (e.g. {@code "Goblin 2"} → the Goblin
     * entry). Trailing numbering / punctuation is stripped, then a fallback chain is tried so engine
     * enemy names still resolve against the SRD 5.2.1 taxonomy, which renamed/regrouped some 2024
     * monsters:
     * <ol>
     *   <li>exact MONSTER title match;</li>
     *   <li>prefix / first-word MONSTER match (so {@code "Goblin"} → {@code "Goblin Warrior"});</li>
     *   <li>exact SPECIES title match (so {@code "Orc"} — a species rather than a monster stat block
     *       in the SRD 5.2.1 — still yields behavioural lore, which carries no combat stats either).</li>
     * </ol>
     */
    public Optional<Entry> monsterEntryByName(String enemyName) {
        if (enemyName == null) return Optional.empty();
        String norm = enemyName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (norm.isEmpty()) return Optional.empty();

        Optional<Entry> exact = byKey.values().stream()
                .filter(e -> "MONSTER".equals(e.type()))
                .filter(e -> e.title().toLowerCase(Locale.ROOT).equals(norm))
                .findFirst();
        if (exact.isPresent()) return exact;

        Optional<Entry> prefix = byKey.values().stream()
                .filter(e -> "MONSTER".equals(e.type()))
                .filter(e -> {
                    String t = e.title().toLowerCase(Locale.ROOT);
                    return t.startsWith(norm + " ") || norm.startsWith(t + " ");
                })
                .findFirst();
        if (prefix.isPresent()) return prefix;

        return byKey.values().stream()
                .filter(e -> "SPECIES".equals(e.type()))
                .filter(e -> e.title().toLowerCase(Locale.ROOT).equals(norm))
                .findFirst();
    }

    /** True if no reference content loaded (dataset missing or malformed). */
    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    /* ── helpers ─────────────────────────────────────────────────── */

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
