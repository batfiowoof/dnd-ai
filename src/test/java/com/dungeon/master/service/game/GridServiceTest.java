package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.GridState;
import com.dungeon.master.model.dto.TerrainCell;
import com.dungeon.master.model.dto.Token;
import com.dungeon.master.model.enums.TerrainType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the pure grid maths used by tactical combat (no DB, no randomness). */
class GridServiceTest {

    private final GridService grid = new GridService();

    /* ── distanceFeet ────────────────────────────────────────────── */

    @Test
    void distanceFeetOrthogonal() {
        assertEquals(15, grid.distanceFeet(0, 0, 3, 0)); // 3 squares east
    }

    @Test
    void distanceFeetDiagonal() {
        assertEquals(15, grid.distanceFeet(0, 0, 3, 3)); // Chebyshev 3 → 15 ft
    }

    @Test
    void distanceFeetMixed() {
        assertEquals(15, grid.distanceFeet(0, 0, 3, 1)); // max(3,1) = 3 → 15 ft
    }

    /* ── pathCostFeet ────────────────────────────────────────────── */

    @Test
    void pathCostOpenTerrainIsFivePerSquare() {
        GridState g = GridState.builder().width(5).height(5).build();
        assertEquals(10, grid.pathCostFeet(g, 0, 0, 2, 0, "me")); // two 5-ft entries
    }

    @Test
    void pathCostDifficultTerrainCostsDouble() {
        GridState g = GridState.builder().width(5).height(5).build();
        g.getTerrain().add(new TerrainCell(1, 0, TerrainType.DIFFICULT));
        // Entering the difficult square costs 10 ft, vs 5 ft on open floor.
        assertEquals(10, grid.pathCostFeet(g, 0, 0, 1, 0, "me"));

        GridState open = GridState.builder().width(5).height(5).build();
        assertEquals(5, grid.pathCostFeet(open, 0, 0, 1, 0, "me"));
    }

    @Test
    void pathCostBlockedByWallsReturnsNull() {
        // 3×3 grid; target (2,2) is fully walled off from the rest.
        GridState g = GridState.builder().width(3).height(3).build();
        g.getTerrain().add(new TerrainCell(1, 1, TerrainType.WALL));
        g.getTerrain().add(new TerrainCell(2, 1, TerrainType.WALL));
        g.getTerrain().add(new TerrainCell(1, 2, TerrainType.WALL));
        assertNull(grid.pathCostFeet(g, 0, 0, 2, 2, "me"));
    }

    @Test
    void pathCostSameSquareIsZero() {
        GridState g = GridState.builder().width(5).height(5).build();
        assertEquals(0, grid.pathCostFeet(g, 2, 2, 2, 2, "me"));
    }

    @Test
    void pathCostCannotCutBetweenTwoWallCorners() {
        // Walls at (1,0) and (0,1) share the corner the diagonal (0,0)->(1,1) would cut.
        GridState g = GridState.builder().width(3).height(3).build();
        g.getTerrain().add(new TerrainCell(1, 0, TerrainType.WALL));
        g.getTerrain().add(new TerrainCell(0, 1, TerrainType.WALL));
        // Direct diagonal is blocked; the only route is the long way round (1,1)->(1,2)->...
        // Easiest assertion: the diagonal is NOT a free 5-ft step — it must cost more.
        Integer cost = grid.pathCostFeet(g, 0, 0, 1, 1, "me");
        assertTrue(cost == null || cost > 5, "diagonal must not squeeze through a walled corner");
    }

    @Test
    void nullGridIsToleratedByQueries() {
        assertNull(grid.pathCostFeet(null, 0, 0, 1, 1, "me"));
        assertTrue(grid.reachable(null, "me", 30).isEmpty());
        assertEquals(0, grid.coverBonus(null, 0, 0, 4, 0));
    }

    /* ── reachable ───────────────────────────────────────────────── */

    @Test
    void reachableOnOpenArenaWithinBudget() {
        GridState g = GridState.builder().width(11).height(11).build();
        g.getTokens().put("me", new Token(5, 5, 0, true, false, false, false, false, false, false, null, false));
        // Budget 5 ft = the 3×3 block around the mover (start + 8 neighbours).
        var cells = grid.reachable(g, "me", 5);
        assertEquals(9, cells.size());
        assertTrue(cells.contains(new GridService.Square(5, 5)));
        assertTrue(cells.contains(new GridService.Square(6, 6)));
    }

    /* ── coverBonus ──────────────────────────────────────────────── */

    @Test
    void coverBonusWallBetweenGivesThreeQuarters() {
        GridState g = GridState.builder().width(5).height(5).build();
        g.getTerrain().add(new TerrainCell(2, 0, TerrainType.WALL));
        assertEquals(5, grid.coverBonus(g, 0, 0, 4, 0)); // wall directly on the line
    }

    @Test
    void coverBonusNoWallGivesNone() {
        GridState g = GridState.builder().width(5).height(5).build();
        assertEquals(0, grid.coverBonus(g, 0, 0, 4, 0));
    }

    /* ── aoeCells ────────────────────────────────────────────────── */

    @Test
    void aoeSphereRadiusOneCoversPlus() {
        List<GridService.Square> cells = grid.aoeCells(0, 0, "sphere", 5); // radius 1 square
        assertEquals(5, cells.size()); // centre + 4 orthogonal
    }

    @Test
    void aoeLineLengthThree() {
        List<GridService.Square> cells = grid.aoeCells(0, 0, "line", 15); // 3 squares
        assertEquals(3, cells.size());
        assertEquals(new GridService.Square(1, 0), cells.get(0));
        assertEquals(new GridService.Square(3, 0), cells.get(2));
    }

    /* ── placeTokens / defaultArena ──────────────────────────────── */

    @Test
    void placeTokensPutsSidesOnOppositeEdges() {
        GridState g = grid.defaultArena(2, 2);
        grid.placeTokens(g, List.of("p1", "p2"), List.of("e1", "e2"));

        assertEquals(4, g.getTokens().size());
        Token p1 = g.getTokens().get("p1");
        Token e1 = g.getTokens().get("e1");
        assertNotNull(p1);
        assertNotNull(e1);
        // Players on the left, enemies on the right.
        assertTrue(p1.getX() < e1.getX());
        assertEquals(1, p1.getX());
        assertEquals(g.getWidth() - 2, e1.getX());
        // Reaction available by default.
        assertTrue(p1.isReactionAvailable());
    }

    /* ── movement targets (enemy AI) ─────────────────────────────── */

    @Test
    void approachSquareEndsAdjacentToTarget() {
        GridState g = GridState.builder().width(11).height(11).build();
        g.getTokens().put("me", new Token(0, 0, 0, true, false, false, false, false, false, false, null, false));
        g.getTokens().put("foe", new Token(5, 5, 0, true, false, false, false, false, false, false, null, false));

        // Speed 30 (6 squares) is enough to close on the foe and end in 5-ft melee reach.
        GridService.Square dest = grid.approachSquare(g, "me", 5, 5, 30, 5);

        assertNotNull(dest);
        assertTrue(grid.distanceFeet(dest.x(), dest.y(), 5, 5) <= 5,
                "approach should end within melee reach of the target");
        assertFalse(dest.x() == 5 && dest.y() == 5, "must never land on the occupied target square");
    }

    @Test
    void fleeSquareIncreasesDistanceFromThreats() {
        GridState g = GridState.builder().width(11).height(11).build();
        g.getTokens().put("me", new Token(5, 5, 0, true, false, false, false, false, false, false, null, false));
        List<GridService.Square> threats = List.of(new GridService.Square(4, 5)); // adjacent pursuer

        int before = grid.distanceFeet(5, 5, 4, 5);
        GridService.Square dest = grid.fleeSquare(g, "me", threats, 30);

        assertNotNull(dest);
        int after = grid.distanceFeet(dest.x(), dest.y(), 4, 5);
        assertTrue(after > before, "flee should increase distance from the nearest threat");
    }

    @Test
    void placeTokensAvoidsWalls() {
        GridState g = grid.defaultArena(1, 1);
        // Drop a wall exactly where the single player would otherwise land (col 1).
        int row = (int) Math.round(1.0 * g.getHeight() / 2);
        g.getTerrain().add(new TerrainCell(1, row, TerrainType.WALL));
        grid.placeTokens(g, List.of("p1"), List.of("e1"));

        Token p1 = g.getTokens().get("p1");
        assertNotNull(p1);
        boolean onWall = g.getTerrain().stream()
                .anyMatch(c -> c.type() == TerrainType.WALL && c.x() == p1.getX() && c.y() == p1.getY());
        assertTrue(!onWall, "token must not be placed on a wall");
    }
}
