package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.MapFeature;
import com.dungeon.master.model.dto.SceneLayout;
import com.dungeon.master.model.dto.TerrainCell;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.enums.TerrainType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pure, deterministic grid maths for tactical combat. No repositories, no
 * persistence, no randomness — every method is a function of its arguments so it
 * is trivially unit-testable and safe to call from anywhere.
 *
 * <h2>5e simplifications</h2>
 * <ul>
 *   <li><b>Squares are 5 ft.</b> Movement is 8-directional. Diagonals cost the
 *       same as orthogonal moves (the PHB "every square is 5 ft" simple variant),
 *       not the optional 5/10 alternating diagonal rule.</li>
 *   <li><b>Difficult terrain</b> doubles the cost to <i>enter</i> a square (10 ft).</li>
 *   <li><b>Walls and occupied squares</b> are fully impassable — you can neither
 *       end in nor move through them (we do not model squeezing or moving through
 *       allies' spaces).</li>
 *   <li><b>Cover</b> is a coarse heuristic on walls only: a wall on the line of
 *       sight gives three-quarters cover (+5), a wall merely grazing the line
 *       gives half cover (+2), otherwise none. See {@link #coverBonus}.</li>
 *   <li><b>AoE templates</b> default to facing east (+x) for directional shapes
 *       (cone, line); see {@link #aoeCells}.</li>
 * </ul>
 */
@Service
public class GridService {

    /** Feet per grid square (5e standard). */
    public static final int FEET_PER_SQUARE = 5;

    /** A grid coordinate; result type for reachability and AoE queries. */
    public record Square(int x, int y) {}

    /* ── distance ────────────────────────────────────────────────── */

    /**
     * Straight-line grid distance in feet using the 5e simple-diagonals rule:
     * Chebyshev distance (max of the axis deltas) × 5 ft. A diagonal step counts
     * as a single 5-ft move.
     */
    public int distanceFeet(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        return Math.max(dx, dy) * FEET_PER_SQUARE;
    }

    /* ── pathing (Dijkstra) ──────────────────────────────────────── */

    /**
     * Minimum movement cost in feet to travel from one square to another over the
     * grid, or {@code null} if the destination is unreachable (out of bounds, a
     * wall, occupied by another token, or fully walled off).
     *
     * <p>Cost to <i>enter</i> a square is 10 ft on {@link TerrainType#DIFFICULT}
     * terrain, else 5 ft. {@link TerrainType#WALL} squares and squares occupied by
     * any token whose key differs from {@code movingRefId} are impassable. Moving
     * zero distance (from == to) costs 0.</p>
     */
    public Integer pathCostFeet(GridState g, int fromX, int fromY, int toX, int toY, String movingRefId) {
        if (g == null || !inBounds(g, toX, toY) || !inBounds(g, fromX, fromY)) {
            return null;
        }
        if (fromX == toX && fromY == toY) {
            return 0;
        }
        if (isBlocked(g, toX, toY, movingRefId)) {
            return null;
        }
        Map<Square, Integer> dist = dijkstra(g, fromX, fromY, movingRefId, Integer.MAX_VALUE);
        return dist.get(new Square(toX, toY));
    }

    /**
     * Every square reachable from the mover's current token position within a
     * movement budget (inclusive), including the starting square (cost 0). Returns
     * an empty set if the mover has no token on the grid.
     */
    public Set<Square> reachable(GridState g, String movingRefId, int budgetFeet) {
        if (g == null) {
            return Set.of();
        }
        Token me = g.getTokens() == null ? null : g.getTokens().get(movingRefId);
        if (me == null) {
            return Set.of();
        }
        Map<Square, Integer> dist = dijkstra(g, me.getX(), me.getY(), movingRefId, budgetFeet);
        return new HashSet<>(dist.keySet());
    }

    /**
     * Dijkstra over the grid from a start square. Returns the minimum entry cost to
     * each square reachable within {@code budgetFeet} (the start square maps to 0).
     * Walls, out-of-bounds, and other tokens' squares are never expanded into.
     */
    private Map<Square, Integer> dijkstra(GridState g, int startX, int startY, String movingRefId, int budgetFeet) {
        Map<Square, Integer> dist = new HashMap<>();
        PriorityQueue<int[]> frontier = new PriorityQueue<>(Comparator.comparingInt(a -> a[2]));
        Square start = new Square(startX, startY);
        dist.put(start, 0);
        frontier.add(new int[]{startX, startY, 0});

        while (!frontier.isEmpty()) {
            int[] cur = frontier.poll();
            int cx = cur[0], cy = cur[1], cost = cur[2];
            if (cost > dist.getOrDefault(new Square(cx, cy), Integer.MAX_VALUE)) {
                continue; // stale entry
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (!inBounds(g, nx, ny) || isBlocked(g, nx, ny, movingRefId)) {
                        continue;
                    }
                    // A diagonal step cannot squeeze between two blocking squares that
                    // share the corner being cut (both orthogonal neighbours blocked).
                    if (dx != 0 && dy != 0
                            && isBlocked(g, cx + dx, cy, movingRefId)
                            && isBlocked(g, cx, cy + dy, movingRefId)) {
                        continue;
                    }
                    int step = enterCost(g, nx, ny);
                    int nd = cost + step;
                    if (nd > budgetFeet) continue;
                    Square ns = new Square(nx, ny);
                    if (nd < dist.getOrDefault(ns, Integer.MAX_VALUE)) {
                        dist.put(ns, nd);
                        frontier.add(new int[]{nx, ny, nd});
                    }
                }
            }
        }
        return dist;
    }

    /* ── tactical movement targets (enemy AI) ────────────────────── */

    /**
     * Among squares reachable within {@code budgetFeet}, the best square from which to
     * MELEE the target at {@code (targetX,targetY)}: it prefers the cheapest square that
     * already sits within {@code reachFeet} of the target (so the mover ends in melee
     * reach without wasting movement); if no reachable square is in reach, it returns the
     * square that gets <i>closest</i> to the target (cheapest path on ties). Returns the
     * mover's current square when no move helps (already adjacent, surrounded, or zero
     * budget), or {@code null} if the mover has no token. Never returns a wall, occupied,
     * or out-of-bounds square ({@link #reachable} excludes those).
     */
    public Square approachSquare(GridState g, String refId, int targetX, int targetY,
                                 int budgetFeet, int reachFeet) {
        Token me = tokenOf(g, refId);
        if (me == null) {
            return null;
        }
        Square current = new Square(me.getX(), me.getY());
        Map<Square, Integer> costs = reachableCosts(g, refId, budgetFeet);
        if (costs.isEmpty()) {
            return current;
        }
        Square inReach = null;
        int inReachCost = Integer.MAX_VALUE;
        Square closest = current;
        int closestDist = distanceFeet(current.x(), current.y(), targetX, targetY);
        int closestCost = costs.getOrDefault(current, 0);
        for (Map.Entry<Square, Integer> e : costs.entrySet()) {
            Square s = e.getKey();
            int cost = e.getValue();
            int dist = distanceFeet(s.x(), s.y(), targetX, targetY);
            if (dist <= reachFeet && cost < inReachCost) {
                inReach = s;
                inReachCost = cost;
            }
            if (dist < closestDist || (dist == closestDist && cost < closestCost)) {
                closest = s;
                closestDist = dist;
                closestCost = cost;
            }
        }
        return inReach != null ? inReach : closest;
    }

    /**
     * A reachable square that keeps the mover within {@code rangeFeet} of the target (so a
     * ranged attacker can still shoot) while MAXIMISING distance from it — kiting away from
     * melee. Cheapest path breaks ties. Falls back to {@link #approachSquare} when no
     * reachable square is in range (the target is too far to shoot from anywhere reachable).
     * Returns {@code null} if the mover has no token.
     */
    public Square kiteSquare(GridState g, String refId, int targetX, int targetY,
                             int budgetFeet, int rangeFeet) {
        Token me = tokenOf(g, refId);
        if (me == null) {
            return null;
        }
        Map<Square, Integer> costs = reachableCosts(g, refId, budgetFeet);
        Square best = null;
        int bestDist = -1;
        int bestCost = Integer.MAX_VALUE;
        for (Map.Entry<Square, Integer> e : costs.entrySet()) {
            Square s = e.getKey();
            int dist = distanceFeet(s.x(), s.y(), targetX, targetY);
            if (dist > rangeFeet) {
                continue; // out of shooting range — not a valid kite square
            }
            int cost = e.getValue();
            if (dist > bestDist || (dist == bestDist && cost < bestCost)) {
                best = s;
                bestDist = dist;
                bestCost = cost;
            }
        }
        return best != null ? best : approachSquare(g, refId, targetX, targetY, budgetFeet, rangeFeet);
    }

    /**
     * A reachable square that MAXIMISES the minimum distance to any of the given
     * {@code threats} (run away from the nearest pursuer); cheapest path breaks ties.
     * Returns the mover's current square when fleeing doesn't help (or there are no
     * threats), or {@code null} if the mover has no token.
     */
    public Square fleeSquare(GridState g, String refId, List<Square> threats, int budgetFeet) {
        Token me = tokenOf(g, refId);
        if (me == null) {
            return null;
        }
        Square current = new Square(me.getX(), me.getY());
        Map<Square, Integer> costs = reachableCosts(g, refId, budgetFeet);
        if (costs.isEmpty() || threats == null || threats.isEmpty()) {
            return current;
        }
        Square best = current;
        int bestMin = minDistanceFeet(current, threats);
        int bestCost = costs.getOrDefault(current, 0);
        for (Map.Entry<Square, Integer> e : costs.entrySet()) {
            Square s = e.getKey();
            int cost = e.getValue();
            int md = minDistanceFeet(s, threats);
            if (md > bestMin || (md == bestMin && cost < bestCost)) {
                best = s;
                bestMin = md;
                bestCost = cost;
            }
        }
        return best;
    }

    /** Reachable squares mapped to their minimum entry cost (start square = 0); empty if no token. */
    private Map<Square, Integer> reachableCosts(GridState g, String movingRefId, int budgetFeet) {
        Token me = tokenOf(g, movingRefId);
        if (me == null) {
            return Map.of();
        }
        return dijkstra(g, me.getX(), me.getY(), movingRefId, budgetFeet);
    }

    private Token tokenOf(GridState g, String refId) {
        return g == null || g.getTokens() == null ? null : g.getTokens().get(refId);
    }

    /** The smallest grid distance (ft) from {@code s} to any threat square. */
    private int minDistanceFeet(Square s, List<Square> threats) {
        int min = Integer.MAX_VALUE;
        for (Square t : threats) {
            min = Math.min(min, distanceFeet(s.x(), s.y(), t.x(), t.y()));
        }
        return min;
    }

    /* ── scene layout → grid ─────────────────────────────────────── */

    /**
     * Build a {@link GridState} from an LLM-designed {@link SceneLayout}: dimensions are
     * clamped (width 10–20, height 8–16), terrain/feature specs outside the clamped bounds
     * are dropped, and terrain type strings are mapped to {@link TerrainType} (unknown types
     * skipped). Tokens are NOT placed here — callers run {@link #placeTokens} afterwards so
     * combatants avoid walls.
     */
    public GridState fromSceneLayout(SceneLayout layout) {
        int width = clamp(layout.width(), 10, 20);
        int height = clamp(layout.height(), 8, 16);

        List<TerrainCell> terrain = new ArrayList<>();
        if (layout.terrain() != null) {
            for (SceneLayout.TerrainCellSpec t : layout.terrain()) {
                if (t == null || t.x() < 0 || t.y() < 0 || t.x() >= width || t.y() >= height) {
                    continue;
                }
                TerrainType type = parseTerrain(t.type());
                if (type != null) {
                    terrain.add(new TerrainCell(t.x(), t.y(), type));
                }
            }
        }

        List<MapFeature> features = new ArrayList<>();
        if (layout.features() != null) {
            for (SceneLayout.FeatureSpec f : layout.features()) {
                if (f == null || f.x() < 0 || f.y() < 0 || f.x() >= width || f.y() >= height) {
                    continue;
                }
                String label = f.label() == null ? "" : f.label().trim();
                if (!label.isEmpty()) {
                    features.add(new MapFeature(f.x(), f.y(), label));
                }
            }
        }

        return GridState.builder()
                .width(width)
                .height(height)
                .terrain(terrain)
                .features(features)
                .build();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static TerrainType parseTerrain(String type) {
        if (type == null) {
            return null;
        }
        try {
            return TerrainType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ── cover ───────────────────────────────────────────────────── */

    /**
     * Cover bonus to the target's AC from intervening walls, using a coarse,
     * deterministic heuristic on the Bresenham line between the two square centres:
     * <ul>
     *   <li>a {@link TerrainType#WALL} square <i>on</i> the line (between the
     *       endpoints) → <b>+5</b> (three-quarters cover);</li>
     *   <li>else a wall square orthogonally adjacent to any point on the line →
     *       <b>+2</b> (half cover);</li>
     *   <li>else <b>0</b>.</li>
     * </ul>
     * Endpoints themselves are ignored so a wall the attacker or target stands
     * against does not grant cover to itself.
     */
    public int coverBonus(GridState g, int fromX, int fromY, int toX, int toY) {
        if (g == null) {
            return 0;
        }
        List<Square> line = bresenham(fromX, fromY, toX, toY);
        boolean partial = false;
        for (Square s : line) {
            if ((s.x() == fromX && s.y() == fromY) || (s.x() == toX && s.y() == toY)) {
                continue; // skip endpoints
            }
            if (terrainAt(g, s.x(), s.y()) == TerrainType.WALL) {
                return 5; // wall directly on the line — three-quarters cover
            }
            // wall grazing the line (orthogonally adjacent) — remember for half cover
            if (terrainAt(g, s.x() + 1, s.y()) == TerrainType.WALL
                    || terrainAt(g, s.x() - 1, s.y()) == TerrainType.WALL
                    || terrainAt(g, s.x(), s.y() + 1) == TerrainType.WALL
                    || terrainAt(g, s.x(), s.y() - 1) == TerrainType.WALL) {
                partial = true;
            }
        }
        return partial ? 2 : 0;
    }

    /* ── area of effect ──────────────────────────────────────────── */

    /**
     * Squares affected by an area-of-effect spell template. {@code shape} matches
     * the {@code SpellEffect.aoeShape} vocabulary ("sphere"/"circle", "cube",
     * "cone", "line"); {@code sizeFeet} is the radius (sphere) or length
     * (cube side / cone length / line length). Feet are converted to squares by
     * integer division (÷5).
     *
     * <p>Simplifications: sphere/circle = every square whose centre is within the
     * radius (Euclidean). Cube = an {@code n×n} block with {@code origin} at its
     * near corner, extending +x/+y. Cone and line default to facing <b>east
     * (+x)</b>; the cone widens 45° per side (half-width = distance from origin).
     * Facing can be parameterised in a later phase.</p>
     */
    public List<Square> aoeCells(int originX, int originY, String shape, int sizeFeet) {
        int n = Math.max(0, sizeFeet / FEET_PER_SQUARE);
        List<Square> cells = new ArrayList<>();
        if (n == 0 || shape == null) {
            return cells;
        }
        String s = shape.toLowerCase();
        switch (s) {
            case "sphere", "circle", "emanation", "cylinder" -> {
                for (int dx = -n; dx <= n; dx++) {
                    for (int dy = -n; dy <= n; dy++) {
                        if (dx * dx + dy * dy <= n * n) {
                            cells.add(new Square(originX + dx, originY + dy));
                        }
                    }
                }
            }
            case "cube", "square" -> {
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        cells.add(new Square(originX + i, originY + j));
                    }
                }
            }
            case "cone" -> {
                for (int dx = 1; dx <= n; dx++) {
                    for (int dy = -dx; dy <= dx; dy++) {
                        cells.add(new Square(originX + dx, originY + dy));
                    }
                }
            }
            case "line" -> {
                for (int i = 1; i <= n; i++) {
                    cells.add(new Square(originX + i, originY));
                }
            }
            default -> { /* unknown shape — no cells */ }
        }
        return cells;
    }

    /* ── arena setup ─────────────────────────────────────────────── */

    /**
     * Build an open, terrain-free arena sized for the given party/enemy counts.
     * Width and height grow with combatant count but never below a 12×10 minimum,
     * giving both sides room to spread out along opposite edges.
     */
    public GridState defaultArena(int playerCount, int enemyCount) {
        int width = Math.max(12, playerCount + enemyCount + 6);
        int height = Math.max(10, Math.max(playerCount, enemyCount) * 2);
        return GridState.builder()
                .width(width)
                .height(height)
                .build();
    }

    /**
     * Place player and enemy tokens on opposite sides of the grid: players in the
     * second column from the left, enemies in the second column from the right,
     * each side spread evenly down its column. The grid is enlarged if it is too
     * small to hold a side. Tokens never land on {@link TerrainType#WALL} squares
     * (the nearest free square in the same column is used instead). Fills
     * {@code g.tokens} keyed by refId.
     */
    public void placeTokens(GridState g, List<String> playerRefIds, List<String> enemyRefIds) {
        int players = playerRefIds == null ? 0 : playerRefIds.size();
        int enemies = enemyRefIds == null ? 0 : enemyRefIds.size();

        int minWidth = Math.max(12, players + enemies + 6);
        int minHeight = Math.max(10, Math.max(players, enemies) * 2);
        if (g.getWidth() < minWidth) g.setWidth(minWidth);
        if (g.getHeight() < minHeight) g.setHeight(minHeight);
        if (g.getTokens() == null) g.setTokens(new LinkedHashMap<>());

        int leftX = 1;
        int rightX = g.getWidth() - 2;

        placeColumn(g, playerRefIds, leftX);
        placeColumn(g, enemyRefIds, rightX);
    }

    /** Spread a side's tokens evenly down a single column, avoiding wall squares. */
    private void placeColumn(GridState g, List<String> refIds, int col) {
        if (refIds == null || refIds.isEmpty()) return;
        int count = refIds.size();
        int height = g.getHeight();
        for (int i = 0; i < count; i++) {
            // Evenly spaced rows: ((i + 1) / (count + 1)) of the column height.
            int row = (int) Math.round((double) (i + 1) * height / (count + 1));
            row = Math.max(0, Math.min(height - 1, row));
            int[] cell = nearestNonWall(g, col, row);
            Token t = new Token();
            t.setX(cell[0]);
            t.setY(cell[1]);
            t.setReactionAvailable(true);
            g.getTokens().put(refIds.get(i), t);
        }
    }

    /**
     * Find the nearest square to (col,row) that is not a wall and not already
     * occupied, via an expanding Chebyshev-ring search outward from the requested
     * cell (so a fully-walled column spills into neighbouring columns rather than
     * failing to place). Falls back to the requested cell if nothing free is found
     * (open arenas always succeed immediately at radius 0).
     */
    private int[] nearestNonWall(GridState g, int col, int row) {
        for (int radius = 0; radius < g.getWidth() + g.getHeight(); radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                    int x = col + dx, y = row + dy;
                    if (!inBounds(g, x, y)) continue;
                    if (terrainAt(g, x, y) == TerrainType.WALL) continue;
                    if (occupied(g, x, y, null)) continue;
                    return new int[]{x, y};
                }
            }
        }
        return new int[]{col, row};
    }

    /* ── helpers ─────────────────────────────────────────────────── */

    private boolean inBounds(GridState g, int x, int y) {
        return x >= 0 && y >= 0 && x < g.getWidth() && y < g.getHeight();
    }

    /** A square is blocked if it is a wall or occupied by a token other than the mover. */
    private boolean isBlocked(GridState g, int x, int y, String movingRefId) {
        return terrainAt(g, x, y) == TerrainType.WALL || occupied(g, x, y, movingRefId);
    }

    /** Cost to enter a square: 10 ft on difficult terrain, else 5 ft. */
    private int enterCost(GridState g, int x, int y) {
        return terrainAt(g, x, y) == TerrainType.DIFFICULT ? 2 * FEET_PER_SQUARE : FEET_PER_SQUARE;
    }

    /** The terrain type at a square, or {@code null} for plain open floor. */
    private TerrainType terrainAt(GridState g, int x, int y) {
        if (g.getTerrain() == null) return null;
        for (TerrainCell c : g.getTerrain()) {
            if (c.x() == x && c.y() == y) return c.type();
        }
        return null;
    }

    /** Whether any token other than {@code exceptRefId} occupies a square. */
    private boolean occupied(GridState g, int x, int y, String exceptRefId) {
        if (g.getTokens() == null) return false;
        for (Map.Entry<String, Token> e : g.getTokens().entrySet()) {
            if (exceptRefId != null && e.getKey().equals(exceptRefId)) continue;
            Token t = e.getValue();
            if (t.getX() == x && t.getY() == y) return true;
        }
        return false;
    }

    /**
     * Bresenham's line algorithm between two grid squares (inclusive of both
     * endpoints). Used by {@link #coverBonus} to walk the line of sight.
     */
    private List<Square> bresenham(int x0, int y0, int x1, int y1) {
        List<Square> cells = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            cells.add(new Square(x, y));
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
        return cells;
    }
}
