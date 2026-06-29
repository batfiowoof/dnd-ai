/* ── Tactical grid client helpers (move preview only) ─────────────
 * These MIRROR the server's GridService rules so the UI can highlight
 * reachable squares and show path costs. The server stays authoritative —
 * a move is only validated/applied backend-side; this is purely visual.
 *
 * Rules (5e, square grid): 1 cell = 5 ft; diagonals cost the same as
 * orthogonals (Chebyshev). Difficult terrain costs 10 ft to enter, normal
 * 5 ft. Walls and other tokens are impassable. A diagonal step may not
 * "cut a corner" between two blocked orthogonal neighbours.
 */

import type { EnemyDto, GridState } from "@/types";

const FEET_PER_CELL = 5;

/** Column letter (A, B, C…) for an x index — used in aria-labels (e.g. "C4"). */
export function colLetter(x: number): string {
  return String.fromCharCode(65 + (x % 26));
}

/** Chebyshev (king-move) distance in feet between two grid cells. */
export function gridDistanceFeet(
  x1: number,
  y1: number,
  x2: number,
  y2: number
): number {
  return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2)) * FEET_PER_CELL;
}

/** Stable "x,y" cell key (matches the keys returned by `reachableSquares`). */
export function cellKey(x: number, y: number): string {
  return `${x},${y}`;
}

/**
 * Dijkstra flood-fill from a combatant's token: returns the set of cell keys
 * ("x,y") reachable within `budgetFeet`, excluding the origin and any occupied
 * cell. Difficult terrain costs 10 ft to enter, normal 5 ft; walls and other
 * tokens block; diagonal corner-cutting between two blocked orthogonals is
 * disallowed. Returns an empty set when there's no budget / token / grid.
 */
export function reachableSquares(
  grid: GridState | null | undefined,
  refId: string,
  budgetFeet: number
): Set<string> {
  const result = new Set<string>();
  if (!grid || budgetFeet <= 0) return result;
  const token = grid.tokens[refId];
  if (!token) return result;

  const inBounds = (x: number, y: number) =>
    x >= 0 && y >= 0 && x < grid.width && y < grid.height;

  const walls = new Set<string>();
  const difficult = new Set<string>();
  for (const t of grid.terrain) {
    if (t.type === "WALL") walls.add(cellKey(t.x, t.y));
    else if (t.type === "DIFFICULT") difficult.add(cellKey(t.x, t.y));
    // HAZARD is passable (it just hurts) — not a movement blocker.
  }

  const occupied = new Set<string>();
  for (const [rid, tk] of Object.entries(grid.tokens)) {
    if (rid !== refId) occupied.add(cellKey(tk.x, tk.y));
  }

  const blocked = (x: number, y: number) =>
    walls.has(cellKey(x, y)) || occupied.has(cellKey(x, y));
  const enterCost = (x: number, y: number) =>
    difficult.has(cellKey(x, y)) ? 10 : 5;

  const startKey = cellKey(token.x, token.y);
  const dist = new Map<string, number>([[startKey, 0]]);
  // Small grids → a plain min-scan frontier is fine (no heap needed).
  const frontier: { x: number; y: number; d: number }[] = [
    { x: token.x, y: token.y, d: 0 },
  ];

  while (frontier.length > 0) {
    let mi = 0;
    for (let i = 1; i < frontier.length; i++) {
      if (frontier[i].d < frontier[mi].d) mi = i;
    }
    const cur = frontier.splice(mi, 1)[0];
    if (cur.d > (dist.get(cellKey(cur.x, cur.y)) ?? Infinity)) continue;

    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        if (dx === 0 && dy === 0) continue;
        const nx = cur.x + dx;
        const ny = cur.y + dy;
        if (!inBounds(nx, ny) || blocked(nx, ny)) continue;
        // No diagonal corner-cutting between two blocked orthogonals.
        if (
          dx !== 0 &&
          dy !== 0 &&
          blocked(cur.x + dx, cur.y) &&
          blocked(cur.x, cur.y + dy)
        ) {
          continue;
        }
        const nd = cur.d + enterCost(nx, ny);
        if (nd > budgetFeet) continue;
        const nk = cellKey(nx, ny);
        if (nd < (dist.get(nk) ?? Infinity)) {
          dist.set(nk, nd);
          frontier.push({ x: nx, y: ny, d: nd });
        }
      }
    }
  }

  for (const k of dist.keys()) {
    if (k !== startKey) result.add(k);
  }
  return result;
}

/**
 * Squares covered by an area-of-effect spell template, anchored at an origin cell.
 * MIRRORS the server's `GridService.aoeCells` so the placement preview matches the
 * authoritative hit set the backend computes from `originX/originY`:
 *
 *  - sphere/circle/emanation/cylinder → Euclidean disc of radius `size/5` squares,
 *    centred on the origin;
 *  - cube/square → an n×n block with the origin at its near (+x/+y) corner;
 *  - cone → a triangle widening 45°/side, apex at the origin, facing east (+x);
 *  - line → a 1-square run east (+x) from the origin.
 *
 * Returns "x,y" cell keys (see {@link cellKey}). The server stays authoritative —
 * this is preview only. Cells are NOT clamped to the grid; the caller skips
 * out-of-bounds keys when rendering.
 */
export function aoeCells(
  originX: number,
  originY: number,
  shape: string | null | undefined,
  sizeFeet: number
): Set<string> {
  const out = new Set<string>();
  const n = Math.max(0, Math.floor(sizeFeet / FEET_PER_CELL));
  if (n === 0 || !shape) return out;

  switch (shape.toLowerCase()) {
    case "sphere":
    case "circle":
    case "emanation":
    case "cylinder":
      for (let dx = -n; dx <= n; dx++) {
        for (let dy = -n; dy <= n; dy++) {
          if (dx * dx + dy * dy <= n * n) {
            out.add(cellKey(originX + dx, originY + dy));
          }
        }
      }
      break;
    case "cube":
    case "square":
      for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
          out.add(cellKey(originX + i, originY + j));
        }
      }
      break;
    case "cone":
      for (let dx = 1; dx <= n; dx++) {
        for (let dy = -dx; dy <= dx; dy++) {
          out.add(cellKey(originX + dx, originY + dy));
        }
      }
      break;
    case "line":
      for (let i = 1; i <= n; i++) {
        out.add(cellKey(originX + i, originY));
      }
      break;
    default:
      break;
  }
  return out;
}

/**
 * Squares within a living enemy's melee reach (cell keys "x,y"). Tinted on the map so the
 * player can avoid provoking opportunity attacks when moving out of them. Enemies with no
 * reach value default to 5 ft. Returns an empty set when there's no grid.
 */
export function threatenedSquares(
  grid: GridState | null | undefined,
  enemies: EnemyDto[]
): Set<string> {
  const set = new Set<string>();
  if (!grid) return set;
  for (const e of enemies) {
    if (!e.alive) continue;
    const et = grid.tokens[e.id];
    if (!et) continue;
    const reach = e.reachFeet > 0 ? e.reachFeet : 5;
    for (let gy = 0; gy < grid.height; gy++) {
      for (let gx = 0; gx < grid.width; gx++) {
        if (gridDistanceFeet(et.x, et.y, gx, gy) <= reach) set.add(cellKey(gx, gy));
      }
    }
  }
  return set;
}

/**
 * Like {@link aoeCells}, but clamped to the grid: only in-bounds cell keys are returned.
 * Used for the placement preview so out-of-board template cells aren't drawn.
 */
export function aoeCellsInBounds(
  originX: number,
  originY: number,
  shape: string | null | undefined,
  sizeFeet: number,
  grid: GridState
): Set<string> {
  const inBounds = new Set<string>();
  for (const k of aoeCells(originX, originY, shape, sizeFeet)) {
    const [cx, cy] = k.split(",").map(Number);
    if (cx >= 0 && cy >= 0 && cx < grid.width && cy < grid.height) {
      inBounds.add(k);
    }
  }
  return inBounds;
}
