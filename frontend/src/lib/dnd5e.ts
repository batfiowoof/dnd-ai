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

import type { ItemKind, InventoryItem } from "@/types";
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

export { kindFromCategory };
