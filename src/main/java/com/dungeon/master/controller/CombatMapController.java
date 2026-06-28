package com.dungeon.master.controller;

import com.dungeon.master.config.AuthUtils;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.service.game.CombatService;
import com.dungeon.master.service.game.GameSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Host-only upload of a battle-map background image for the active combat encounter.
 * The file is stored under {@code app.uploads.dir} with a random name (no client-supplied
 * path is ever used), exposed via {@code /uploads/**}, and pinned onto the encounter's grid
 * so every client re-renders with the new map.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}")
@RequiredArgsConstructor
@Slf4j
public class CombatMapController {

    private final GameSessionService gameSessionService;
    private final CombatService combatService;

    @Value("${app.uploads.dir:./uploads}")
    private String uploadsDir;

    /** Reject anything larger than 5 MB (also enforced by the servlet multipart limits). */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /**
     * Allowlist of safe raster image types → canonical extension. SVG and HTML are deliberately
     * excluded: they can carry inline scripts and, served from our own origin under {@code /uploads},
     * would be a stored-XSS vector. The extension is derived solely from this map — the client
     * filename/extension is never used — so a crafted ".svg"/".html" name cannot be stored.
     */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    @PostMapping(value = "/combat/map", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadMap(@PathVariable UUID sessionId,
                                       @RequestParam("file") MultipartFile file,
                                       @AuthenticationPrincipal Jwt jwt) {
        String username = AuthUtils.username(jwt);
        GameSession session = gameSessionService.getSession(sessionId);
        if (session.getCreatedBy() == null || !session.getCreatedBy().equals(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only the host can set the battle map"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded"));
        }
        String contentType = file.getContentType();
        String ext = contentType == null ? null : ALLOWED_TYPES.get(contentType.toLowerCase(Locale.ROOT));
        if (ext == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only JPEG, PNG, WebP and GIF images are accepted"));
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "Image exceeds the 5 MB limit"));
        }

        try {
            Path dir = Paths.get(uploadsDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            // The extension comes only from the allowlisted content type; the client filename is
            // never trusted — no path-traversal and no script-bearing extension can be stored.
            String name = UUID.randomUUID() + "." + ext;
            Path dest = dir.resolve(name);
            file.transferTo(dest);

            String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/uploads/").path(name).toUriString();

            // Pin onto the active encounter (409 if none) and broadcast the refreshed state.
            combatService.setMapBackground(sessionId, url);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IllegalStateException e) {
            // No active encounter (or no grid) — a map can only be set during combat.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage() == null ? "No active encounter" : e.getMessage()));
        } catch (IOException e) {
            log.error("Failed to store battle map for session={}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to store image"));
        }
    }
}
