/* ── D&D 2024 (SRD 5.2.1) rules & helpers for character creation ───
 * Reference *content* (species, backgrounds, classes, feats, spells, equipment)
 * is sourced from the app backend `/api/srd/*` via `hooks/useDnd5eData.ts`. This
 * module holds the rules math, the UI model shapes those hooks produce, the
 * background ability-score-increase (ASI) logic, and a best-effort parser that
 * turns the SRD's free-text starting-equipment strings into an inventory list.
 *
 * 2024 rules note: ability scores = a chosen base (Standard Array / Point Buy)
 * PLUS the *background's* increase. Species grants traits only — no ability
 * bonuses (the old `RaceInfo.abilityBonuses` is gone).
 */

import type { ItemKind, InventoryItem, GridState } from "@/types";
import { kindFromCategory } from "@/lib/dnd5eapi";

/* ── UI models produced by the data hooks ────────────────────────── */

export interface SpeciesInfo {
  index: string;
  name: string;
  creatureType: string;
  size: string;
  /** Parsed from the SRD speed string (falls back to 30). */
  speed: number;
  traits: { name: string; desc: string }[];
}

export interface BackgroundInfo {
  index: string;
  name: string;
  /** The three abilities the background's increase may be assigned to. */
  abilityScores: string[];
  /** Origin feat, e.g. "Magic Initiate (Cleric)". */
  feat: string;
  skillProficiencies: string[];
  toolProficiency: string;
  equipment: { optionA: string; optionB: string };
}

export interface ClassInfo {
  index: string;
  name: string;
  primaryAbility: string;
  hitDie: number;
  savingThrows: string[];
  skillProficiencies: { choose: number; from: string[] };
  weaponProficiencies: string;
  armorTraining: string[];
  startingEquipment: string;
}

/* ── Static rules data ───────────────────────────────────────────── */

export const STANDARD_ARRAY = [15, 14, 13, 12, 10, 8];

/**
 * The 18 canonical D&D skills. Used as the class skill-choice option list when a
 * class's `skillProficiencies.from` is empty — in the 2024 rules that means
 * "choose any N from the full skill list" (e.g. Bard: choose 3 from any).
 */
export const ALL_SKILLS = [
  "Acrobatics",
  "Animal Handling",
  "Arcana",
  "Athletics",
  "Deception",
  "History",
  "Insight",
  "Intimidation",
  "Investigation",
  "Medicine",
  "Nature",
  "Perception",
  "Performance",
  "Persuasion",
  "Religion",
  "Sleight of Hand",
  "Stealth",
  "Survival",
] as const;

/** The skill option list for a class: its `from`, or the full list when empty. */
export function classSkillOptions(cls: ClassInfo | null): string[] {
  if (!cls) return [];
  return cls.skillProficiencies.from.length > 0
    ? cls.skillProficiencies.from
    : [...ALL_SKILLS];
}

export const POINT_BUY_COSTS: Record<number, number> = {
  8: 0, 9: 1, 10: 2, 11: 3, 12: 4, 13: 5, 14: 7, 15: 9,
};

export const POINT_BUY_TOTAL = 27;

export const ABILITY_NAMES = [
  "strength",
  "dexterity",
  "constitution",
  "intelligence",
  "wisdom",
  "charisma",
] as const;

export type AbilityName = (typeof ABILITY_NAMES)[number];

/** Map a background's display ability ("Intelligence") to an `AbilityName`. */
export function toAbilityName(display: string): AbilityName | null {
  const lc = display.trim().toLowerCase();
  return (ABILITY_NAMES as readonly string[]).includes(lc)
    ? (lc as AbilityName)
    : null;
}

export function getAbilityModifier(score: number): number {
  return Math.floor((score - 10) / 2);
}

export function formatModifier(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

/** Level-1 max HP = hit die + CON modifier. */
export function calculateHitPoints(hitDie: number, constitution: number): number {
  return hitDie + getAbilityModifier(constitution);
}

export function calculateArmorClass(dexterity: number): number {
  return 10 + getAbilityModifier(dexterity);
}

/* ── Background ability-score increase (2024) ────────────────────── */

/** `two-one` = +2 to one ability and +1 to another; `all-one` = +1 to all three. */
export type AsiMode = "two-one" | "all-one";

export interface AsiAssignment {
  mode: AsiMode;
  /** Target of the +2 (two-one mode only). */
  plusTwo: AbilityName | null;
  /** Target of the +1 (two-one mode only). */
  plusOne: AbilityName | null;
}

export const EMPTY_ASI: AsiAssignment = {
  mode: "two-one",
  plusTwo: null,
  plusOne: null,
};

/** The background's three target abilities as `AbilityName`s (skips unknowns). */
export function backgroundTargets(bg: BackgroundInfo | null): AbilityName[] {
  if (!bg) return [];
  return bg.abilityScores
    .map(toAbilityName)
    .filter((a): a is AbilityName => a !== null);
}

/** Per-ability bonus granted by the background ASI (all zero until valid). */
export function backgroundBonuses(
  bg: BackgroundInfo | null,
  asi: AsiAssignment
): Record<AbilityName, number> {
  const bonuses = Object.fromEntries(
    ABILITY_NAMES.map((a) => [a, 0])
  ) as Record<AbilityName, number>;
  if (!bg) return bonuses;

  if (asi.mode === "all-one") {
    for (const a of backgroundTargets(bg)) bonuses[a] += 1;
  } else {
    if (asi.plusTwo) bonuses[asi.plusTwo] += 2;
    if (asi.plusOne) bonuses[asi.plusOne] += 1;
  }
  return bonuses;
}

/** True when the chosen ASI split is a legal assignment for this background. */
export function asiValid(bg: BackgroundInfo | null, asi: AsiAssignment): boolean {
  if (!bg) return false;
  if (asi.mode === "all-one") return backgroundTargets(bg).length === 3;
  const targets = backgroundTargets(bg);
  return (
    !!asi.plusTwo &&
    !!asi.plusOne &&
    asi.plusTwo !== asi.plusOne &&
    targets.includes(asi.plusTwo) &&
    targets.includes(asi.plusOne)
  );
}

/* ── Starting equipment (best-effort parse of SRD free text) ─────── */

export interface EquipOption {
  /** "A" / "B" / "C". */
  letter: string;
  /** The raw item list for this option (display + parsing source). */
  raw: string;
}

/**
 * Split a class `startingEquipment` string into its A/B(/C) options.
 * Example: "Choose A or B: (A) Greataxe, …; or (B) 75 GP" → two options.
 * Falls back to a single option containing the whole string.
 */
export function parseClassEquipmentOptions(
  startingEquipment: string
): EquipOption[] {
  const out: EquipOption[] = [];
  const re = /\(([A-Z])\)\s*([^;]*?)(?=\s*;|\s*$)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(startingEquipment)) !== null) {
    out.push({ letter: m[1], raw: m[2].trim() });
  }
  if (out.length === 0) {
    out.push({ letter: "A", raw: startingEquipment.trim() });
  }
  return out;
}

const WEAPON_RE =
  /axe|sword|dagger|mace|spear|bow|javelin|flail|sickle|quarterstaff|staff|club|hammer|rapier|scimitar|glaive|halberd|pike|whip|crossbow|sling|dart|trident|morningstar|maul|war ?pick/i;
const ARMOR_RE = /armor|mail|shield|leather|plate|breastplate|chain shirt/i;

/** Heuristic item kind from a name (used when the SRD list has no exact match). */
function guessKind(name: string): ItemKind {
  if (ARMOR_RE.test(name)) return "ARMOR";
  if (WEAPON_RE.test(name)) return "WEAPON";
  return "GEAR";
}

/**
 * Parse one option's raw item string into a merged inventory list. Tokens are
 * comma-separated; a leading integer is treated as a quantity ("4 Handaxes").
 * `kindMap` (keyed by SRD index) classifies known items; everything else falls
 * back to a keyword heuristic, then GEAR. Money ("75 GP") and packs stay as
 * generic gear — this is intentionally simpler than a structured item builder.
 */
export function parseEquipmentItems(
  raw: string,
  kindMap?: Map<string, ItemKind>
): InventoryItem[] {
  const kindOf = (name: string): ItemKind => {
    if (kindMap) {
      const idx = name.toLowerCase().trim().replace(/[^a-z0-9]+/g, "-");
      const hit = kindMap.get(idx) ?? kindMap.get(idx.replace(/s$/, ""));
      if (hit) return hit;
    }
    return guessKind(name);
  };

  const merged: InventoryItem[] = [];
  raw
    .split(",")
    .map((t) => t.trim().replace(/^and\s+/i, "").trim())
    .filter(Boolean)
    .forEach((token) => {
      const m = /^(\d+)\s+(.+)$/.exec(token);
      const qty = m ? Number(m[1]) : 1;
      const name = (m ? m[2] : token).trim();
      if (!name) return;
      const kind = kindOf(name);
      const existing = merged.find((i) => i.name === name && i.kind === kind);
      if (existing) existing.qty += qty;
      else merged.push({ name, qty, kind, equipped: false });
    });
  return merged;
}

/** Human-readable strings for the legacy `equipment` field / display. */
export function equipmentToStrings(items: InventoryItem[]): string[] {
  return items.map((i) => (i.qty > 1 ? `${i.name} (${i.qty})` : i.name));
}

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

const FEET_PER_CELL = 5;

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

export { kindFromCategory };
