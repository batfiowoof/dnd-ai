package com.dungeon.master.service.game;

import com.dungeon.master.exception.SessionNotFoundException;
import com.dungeon.master.model.dto.RegionNode;
import com.dungeon.master.model.dto.TravelMapDto;
import com.dungeon.master.model.dto.WorldRegion;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.World;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.WorldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the travel-map read model for a session and owns the single source of truth for the
 * location graph — resolving each authored {@link WorldRegion} into a positioned {@link RegionNode}
 * (auto-laying-out any the author never placed) and symmetrizing the route connections. Both the map
 * endpoint and {@link TravelService} (which needs adjacency + coordinate distance) read the nodes
 * from here so what the player sees and what the server validates always agree.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TravelMapService {

    /** Auto-layout ring radius as a fraction of the 0–100 canvas, centered at (50, 50). */
    private static final double RING_RADIUS = 35.0;

    private final GameSessionRepository sessionRepository;
    private final WorldRepository worldRepository;

    /** Full map read model for the session (empty region list when there's no authored world). */
    public TravelMapDto buildMap(UUID sessionId) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
        return new TravelMapDto(nodesForSession(session), session.getCurrentRegion(),
                session.getInGameMinutes(), session.getTravelPace());
    }

    /** The resolved location graph for a session, or an empty list when it has no authored world. */
    public List<RegionNode> nodesForSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(this::nodesForSession)
                .orElse(List.of());
    }

    private List<RegionNode> nodesForSession(GameSession session) {
        if (session.getWorldId() == null) {
            return List.of();
        }
        World world = worldRepository.findById(session.getWorldId()).orElse(null);
        if (world == null || world.getRegions() == null || world.getRegions().isEmpty()) {
            return List.of();
        }
        return resolveNodes(world.getRegions());
    }

    /**
     * Turn authored regions into positioned, route-connected nodes: drop nameless entries, fill in a
     * deterministic ring position for any region missing coordinates, and make connections undirected
     * (an edge A→B implies B→A) using case-insensitive name matching against the real region set. When
     * a world defines no routes at all, a minimum-spanning-tree road network is synthesized over the
     * positions so travel is always possible (every location reachable) even before the map is authored.
     */
    static List<RegionNode> resolveNodes(List<WorldRegion> regions) {
        // Keep only named regions, de-duplicated by canonical (lower-cased) name.
        Map<String, WorldRegion> byCanonical = new LinkedHashMap<>();
        for (WorldRegion r : regions) {
            if (r == null || r.name() == null || r.name().isBlank()) {
                continue;
            }
            byCanonical.putIfAbsent(canonical(r.name()), r);
        }
        List<WorldRegion> kept = new ArrayList<>(byCanonical.values());
        int n = kept.size();

        // Resolve positions up front (authored or auto-laid-out) — needed for the MST fallback too.
        double[][] pos = new double[n][];
        for (int i = 0; i < n; i++) {
            pos[i] = position(kept.get(i), i, n);
        }

        // Undirected adjacency: resolve each connection name to a real region and mirror the edge.
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (WorldRegion r : kept) {
            edges.put(canonical(r.name()), new LinkedHashSet<>());
        }
        int authoredEdges = 0;
        for (WorldRegion r : kept) {
            if (r.connections() == null) {
                continue;
            }
            String from = canonical(r.name());
            for (String conn : r.connections()) {
                if (conn == null || conn.isBlank()) {
                    continue;
                }
                String to = canonical(conn);
                if (to.equals(from) || !edges.containsKey(to)) {
                    continue; // skip self-links and connections to unknown regions
                }
                if (edges.get(from).add(to)) {
                    edges.get(to).add(from);
                    authoredEdges++;
                }
            }
        }

        if (authoredEdges == 0 && n > 1) {
            synthesizeRoads(kept, pos, edges);
        }

        List<RegionNode> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            WorldRegion r = kept.get(i);
            List<String> conns = edges.get(canonical(r.name())).stream()
                    .map(c -> byCanonical.get(c).name())
                    .toList();
            nodes.add(new RegionNode(r.name(), nullToEmpty(r.type()), nullToEmpty(r.description()),
                    pos[i][0], pos[i][1], conns));
        }
        return nodes;
    }

    /**
     * Build a minimum spanning tree (Prim's) over the node positions and add its edges, so a world
     * with no authored routes still gets a fully-connected, natural-looking road network.
     */
    private static void synthesizeRoads(List<WorldRegion> kept, double[][] pos,
                                        Map<String, Set<String>> edges) {
        int n = kept.size();
        boolean[] inTree = new boolean[n];
        inTree[0] = true;
        for (int added = 1; added < n; added++) {
            int bestFrom = -1;
            int bestTo = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!inTree[i]) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    if (inTree[j]) {
                        continue;
                    }
                    double d = dist(pos[i], pos[j]);
                    if (d < bestDist) {
                        bestDist = d;
                        bestFrom = i;
                        bestTo = j;
                    }
                }
            }
            if (bestTo < 0) {
                break;
            }
            inTree[bestTo] = true;
            String a = canonical(kept.get(bestFrom).name());
            String b = canonical(kept.get(bestTo).name());
            edges.get(a).add(b);
            edges.get(b).add(a);
        }
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Authored coordinates when present and valid, else a deterministic ring slot by index. */
    private static double[] position(WorldRegion r, int index, int total) {
        if (r.x() != null && r.y() != null) {
            return new double[] {clamp(r.x()), clamp(r.y())};
        }
        double angle = 2 * Math.PI * index / Math.max(1, total) - Math.PI / 2; // start at top
        double x = 50 + RING_RADIUS * Math.cos(angle);
        double y = 50 + RING_RADIUS * Math.sin(angle);
        return new double[] {clamp(x), clamp(y)};
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static String canonical(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
