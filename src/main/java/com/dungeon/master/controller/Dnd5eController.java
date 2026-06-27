package com.dungeon.master.controller;

import com.dungeon.master.service.game.Dnd5eReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves the bundled 2024 SRD 5.2.1 structured character-creation reference data under
 * {@code /api/srd/*}. This replaces the previous external {@code dnd5eapi.co} dependency with the
 * app's own copy (see {@link Dnd5eReferenceService} for loading + the CC-BY-4.0 NOTICE).
 *
 * <p><strong>Response shapes (for the J3 frontend client):</strong>
 * <ul>
 *   <li>List endpoints return a <em>plain JSON array</em> of the per-type records, exactly as stored
 *       in {@code srd-5.2.1-structured.json} (each carries a stable {@code index} field).</li>
 *   <li>Detail endpoints return the single record object, or {@code 404} when the {@code index} is
 *       unknown.</li>
 *   <li>{@code /alignments} returns an array of {@code {index, name}} objects (the nine standard
 *       SRD alignments — not present in the structured file, served statically).</li>
 * </ul>
 *
 * <p>Records are plain maps so they serialize cleanly under Spring Boot 4 / Jackson 3. This is public
 * reference data: {@code /api/srd/**} is permitted without auth in the security config.
 */
@RestController
@RequestMapping("/api/srd")
@RequiredArgsConstructor
public class Dnd5eController {

    private final Dnd5eReferenceService reference;

    /* ── species ─────────────────────────────────────────────────── */

    @GetMapping("/species")
    public List<Map<String, Object>> listSpecies() {
        return reference.listSpecies();
    }

    @GetMapping("/species/{index}")
    public ResponseEntity<Map<String, Object>> getSpecies(@PathVariable String index) {
        return reference.getSpecies(index).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ── backgrounds ─────────────────────────────────────────────── */

    @GetMapping("/backgrounds")
    public List<Map<String, Object>> listBackgrounds() {
        return reference.listBackgrounds();
    }

    @GetMapping("/backgrounds/{index}")
    public ResponseEntity<Map<String, Object>> getBackground(@PathVariable String index) {
        return reference.getBackground(index).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ── classes ─────────────────────────────────────────────────── */

    @GetMapping("/classes")
    public List<Map<String, Object>> listClasses() {
        return reference.listClasses();
    }

    @GetMapping("/classes/{index}")
    public ResponseEntity<Map<String, Object>> getClass(@PathVariable String index) {
        return reference.getClass(index).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/classes/{index}/spells")
    public ResponseEntity<List<Map<String, Object>>> getClassSpells(@PathVariable String index) {
        if (reference.getClass(index).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reference.spellsForClass(index));
    }

    /* ── feats ───────────────────────────────────────────────────── */

    @GetMapping("/feats")
    public List<Map<String, Object>> listFeats() {
        return reference.listFeats();
    }

    @GetMapping("/feats/{index}")
    public ResponseEntity<Map<String, Object>> getFeat(@PathVariable String index) {
        return reference.getFeat(index).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ── spells ──────────────────────────────────────────────────── */

    @GetMapping("/spells/{index}")
    public ResponseEntity<Map<String, Object>> getSpell(@PathVariable String index) {
        return reference.getSpell(index).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ── equipment ───────────────────────────────────────────────── */

    @GetMapping("/equipment")
    public List<Map<String, Object>> listEquipment() {
        return reference.listEquipment();
    }

    @GetMapping("/equipment/{index}")
    public ResponseEntity<Map<String, Object>> getEquipment(@PathVariable String index) {
        return reference.getEquipment(index).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ── alignments ──────────────────────────────────────────────── */

    @GetMapping("/alignments")
    public List<Map<String, Object>> alignments() {
        return reference.alignments();
    }
}
