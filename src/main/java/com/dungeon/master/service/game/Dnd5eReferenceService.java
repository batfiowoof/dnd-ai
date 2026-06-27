package com.dungeon.master.service.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the bundled 2024 SRD 5.2.1 structured character-creation corpus
 * ({@code resources/dnd5e/srd-5.2.1-structured.json}) into memory once at startup and exposes it for
 * the {@code /api/srd/*} reference endpoints. The file is a single JSON object with the keys
 * {@code backgrounds}, {@code species}, {@code classes}, {@code feats}, {@code spells}, and
 * {@code equipment}; each value is an array of records carrying a stable {@code index} field.
 *
 * <p>Records are parsed into plain {@link Map}/{@link List} structures (not Jackson {@code JsonNode}s):
 * under Spring Boot 4 the HTTP layer serializes with Jackson 3, which does not recognise a Jackson 2
 * {@code JsonNode} and would emit its internal getters instead of the data. Plain maps/lists serialize
 * cleanly under any Jackson version. The service fails soft — a missing or malformed file logs a
 * warning and leaves the lookups empty rather than blocking startup.
 *
 * <p>See {@code NOTICE} — content derived from the SRD 5.2.1 under CC-BY-4.0.
 */
@Component
@Slf4j
public class Dnd5eReferenceService {

    private static final String RESOURCE = "dnd5e/srd-5.2.1-structured.json";

    /** The nine standard SRD alignments, served statically (not present in the structured file). */
    private static final List<String> ALIGNMENT_NAMES = List.of(
            "Lawful Good", "Neutral Good", "Chaotic Good",
            "Lawful Neutral", "Neutral", "Chaotic Neutral",
            "Lawful Evil", "Neutral Evil", "Chaotic Evil");

    // Self-instantiated (not injected): only used to read a bundled classpath resource once at startup.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Full ordered lists per type (used by the list endpoints). */
    private final List<Map<String, Object>> backgrounds = new ArrayList<>();
    private final List<Map<String, Object>> species = new ArrayList<>();
    private final List<Map<String, Object>> classes = new ArrayList<>();
    private final List<Map<String, Object>> feats = new ArrayList<>();
    private final List<Map<String, Object>> spells = new ArrayList<>();
    private final List<Map<String, Object>> equipment = new ArrayList<>();

    /** Per-type lookups keyed by the lower-cased {@code index}. */
    private final Map<String, Map<String, Object>> backgroundsByIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> speciesByIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> classesByIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> featsByIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> spellsByIndex = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> equipmentByIndex = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in, new TypeReference<>() {});
            index(root.get("backgrounds"), backgrounds, backgroundsByIndex);
            index(root.get("species"), species, speciesByIndex);
            index(root.get("classes"), classes, classesByIndex);
            index(root.get("feats"), feats, featsByIndex);
            index(root.get("spells"), spells, spellsByIndex);
            index(root.get("equipment"), equipment, equipmentByIndex);
            log.info("Loaded SRD 5.2.1 structured corpus from {}: {} backgrounds, {} species, "
                            + "{} classes, {} feats, {} spells, {} equipment",
                    RESOURCE, backgrounds.size(), species.size(), classes.size(),
                    feats.size(), spells.size(), equipment.size());
        } catch (Exception e) {
            log.warn("Failed to load SRD structured corpus {} — no reference data loaded: {}",
                    RESOURCE, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void index(Object arr, List<Map<String, Object>> list,
                              Map<String, Map<String, Object>> byIndex) {
        if (!(arr instanceof List<?> entries)) {
            return;
        }
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> rec = (Map<String, Object>) m;
            list.add(rec);
            Object idx = rec.get("index");
            if (idx != null) {
                String key = idx.toString().trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) {
                    byIndex.put(key, rec);
                }
            }
        }
    }

    /* ── backgrounds ─────────────────────────────────────────────── */

    public List<Map<String, Object>> listBackgrounds() {
        return List.copyOf(backgrounds);
    }

    public Optional<Map<String, Object>> getBackground(String index) {
        return lookup(backgroundsByIndex, index);
    }

    /* ── species ─────────────────────────────────────────────────── */

    public List<Map<String, Object>> listSpecies() {
        return List.copyOf(species);
    }

    public Optional<Map<String, Object>> getSpecies(String index) {
        return lookup(speciesByIndex, index);
    }

    /* ── classes ─────────────────────────────────────────────────── */

    public List<Map<String, Object>> listClasses() {
        return List.copyOf(classes);
    }

    public Optional<Map<String, Object>> getClass(String index) {
        return lookup(classesByIndex, index);
    }

    /* ── feats ───────────────────────────────────────────────────── */

    public List<Map<String, Object>> listFeats() {
        return List.copyOf(feats);
    }

    public Optional<Map<String, Object>> getFeat(String index) {
        return lookup(featsByIndex, index);
    }

    /* ── spells ──────────────────────────────────────────────────── */

    public Optional<Map<String, Object>> getSpell(String index) {
        return lookup(spellsByIndex, index);
    }

    /**
     * Every spell whose {@code classes} array contains the given class's display {@code name}
     * (case-insensitive). The {@code index} is resolved to its class record first so the public-facing
     * key (e.g. {@code "wizard"}) maps onto the human name stored on each spell (e.g. {@code "Wizard"}).
     * Returns an empty list for an unknown class index.
     */
    public List<Map<String, Object>> spellsForClass(String index) {
        Optional<Map<String, Object>> clazz = getClass(index);
        if (clazz.isEmpty()) {
            return List.of();
        }
        Object nameObj = clazz.get().get("name");
        String className = nameObj == null ? "" : nameObj.toString().trim().toLowerCase(Locale.ROOT);
        if (className.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> spell : spells) {
            if (spell.get("classes") instanceof List<?> spellClasses) {
                for (Object c : spellClasses) {
                    if (c != null && c.toString().trim().toLowerCase(Locale.ROOT).equals(className)) {
                        result.add(spell);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /* ── equipment ───────────────────────────────────────────────── */

    public List<Map<String, Object>> listEquipment() {
        return List.copyOf(equipment);
    }

    public Optional<Map<String, Object>> getEquipment(String index) {
        return lookup(equipmentByIndex, index);
    }

    /* ── alignments ──────────────────────────────────────────────── */

    /** The nine standard SRD alignments as {@code {index, name}} objects. */
    public List<Map<String, Object>> alignments() {
        List<Map<String, Object>> result = new ArrayList<>(ALIGNMENT_NAMES.size());
        for (String name : ALIGNMENT_NAMES) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("index", name.toLowerCase(Locale.ROOT).replace(' ', '-'));
            node.put("name", name);
            result.add(node);
        }
        return result;
    }

    /** True if no structured reference data loaded (dataset missing or malformed). */
    public boolean isEmpty() {
        return backgrounds.isEmpty() && species.isEmpty() && classes.isEmpty()
                && feats.isEmpty() && spells.isEmpty() && equipment.isEmpty();
    }

    /* ── helpers ─────────────────────────────────────────────────── */

    private static Optional<Map<String, Object>> lookup(Map<String, Map<String, Object>> byIndex,
                                                         String index) {
        if (index == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byIndex.get(index.trim().toLowerCase(Locale.ROOT)));
    }
}
