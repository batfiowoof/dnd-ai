/* ── App SRD reference client (2024 SRD 5.2.1) ────────────────────
 * The character-creation reference data (species, backgrounds, classes, feats,
 * spells, equipment) is served by the app's own backend under `/api/srd/*` —
 * this replaced the external dnd5eapi.co (2014 ruleset) dependency in J2.
 *
 * `/api/srd/**` is public (permitted without auth in the security config) and is
 * proxied to the backend by `next.config.ts` (`/api/:path* → backend`), so a
 * plain relative `fetch` with no token works in both dev and prod. List
 * endpoints return a plain JSON array of full records; detail endpoints return
 * the single record (404 on an unknown index). React Query hooks live in
 * `hooks/useDnd5eData.ts` / `hooks/useSrdInfo.ts`, which map these to UI models.
 */

import type { ItemKind } from "@/types";

const SRD_API = "/api/srd";

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${SRD_API}${path}`);
  if (!res.ok) {
    throw new Error(`/api/srd${path} failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

/* ── Raw record shapes (as stored in srd-5.2.1-structured.json) ───── */

export interface SrdTrait {
  name: string;
  desc: string;
}

export interface SrdSpecies {
  index: string;
  name: string;
  creatureType: string;
  size: string;
  /** Free text, e.g. "30 feet" (empty for some species). Parse with `parseSpeed`. */
  speed: string;
  traits: SrdTrait[];
}

export interface SrdBackground {
  index: string;
  name: string;
  /** The three abilities this background's score increase may be assigned to. */
  abilityScores: string[];
  /** Origin feat, e.g. "Magic Initiate (Cleric)". */
  feat: string;
  skillProficiencies: string[];
  toolProficiency: string;
  equipment: { optionA: string; optionB: string };
}

export interface SrdClass {
  index: string;
  name: string;
  primaryAbility: string;
  hitDie: number;
  savingThrows: string[];
  skillProficiencies: { choose: number; from: string[] };
  weaponProficiencies: string;
  armorTraining: string[];
  /** Free text, e.g. "Choose A or B: (A) …; or (B) 75 GP". Parse with `parseEquipmentOptions`. */
  startingEquipment: string;
}

export interface SrdFeat {
  index: string;
  name: string;
  category: string;
  prerequisite?: string | null;
  desc: string;
}

export interface SrdSpell {
  index: string;
  name: string;
  level: number;
  school: string;
  classes: string[];
  castingTime: string;
  range: string;
  components: string;
  duration: string;
  concentration: boolean;
  ritual: boolean;
  desc: string;
  higherLevel?: string;
}

export interface SrdEquipment {
  index: string;
  name: string;
  /** "weapon" | "armor" | "gear". */
  category: string;
  /** Weapons. */
  weaponType?: string;
  damage?: string;
  damageType?: string;
  mastery?: string;
  /** Armor. */
  armorCategory?: string;
  ac?: string;
  baseAc?: number;
  /** Common. */
  weight?: string;
  cost?: string;
}

export interface SrdAlignment {
  index: string;
  name: string;
}

/* ── Endpoints ───────────────────────────────────────────────────── */

export const listSpecies = () => apiGet<SrdSpecies[]>("/species");
export const getSpecies = (index: string) =>
  apiGet<SrdSpecies>(`/species/${index}`);

export const listBackgrounds = () => apiGet<SrdBackground[]>("/backgrounds");
export const getBackground = (index: string) =>
  apiGet<SrdBackground>(`/backgrounds/${index}`);

export const listClasses = () => apiGet<SrdClass[]>("/classes");
export const getClass = (index: string) => apiGet<SrdClass>(`/classes/${index}`);
export const listClassSpells = (index: string) =>
  apiGet<SrdSpell[]>(`/classes/${index}/spells`);

export const listFeats = () => apiGet<SrdFeat[]>("/feats");
export const getFeat = (index: string) => apiGet<SrdFeat>(`/feats/${index}`);

export const getSpell = (index: string) => apiGet<SrdSpell>(`/spells/${index}`);

export const listEquipment = () => apiGet<SrdEquipment[]>("/equipment");
export const getEquipment = (index: string) =>
  apiGet<SrdEquipment>(`/equipment/${index}`);

export const listAlignments = () => apiGet<SrdAlignment[]>("/alignments");

/* ── Helpers ─────────────────────────────────────────────────────── */

/**
 * Best-effort SRD index from a display name (e.g. "Acid Arrow" → "acid-arrow").
 * The structured corpus derives indexes this way, so it resolves most content;
 * unknown / custom names simply 404 (callers fail silently).
 */
export function nameToIndex(name: string): string {
  return name
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

/** Parse a species speed string ("30 feet") to a number; falls back to 30. */
export function parseSpeed(speed: string | undefined): number {
  const m = /(\d+)/.exec(speed ?? "");
  return m ? Number(m[1]) : 30;
}

/**
 * Resolve a background feat string to a feat index. The structured feats carry
 * the base name ("Magic Initiate"); backgrounds qualify it ("Magic Initiate
 * (Cleric)"), so strip the parenthetical before deriving the index.
 */
export function featIndexFromName(featName: string): string {
  const base = featName.split("(")[0].trim();
  return nameToIndex(base);
}

/** Map an SRD equipment category to our inventory `ItemKind`. */
export function kindFromCategory(category: string | undefined): ItemKind {
  switch ((category ?? "").toLowerCase()) {
    case "weapon":
      return "WEAPON";
    case "armor":
      return "ARMOR";
    default:
      return "GEAR";
  }
}
