import type { Milestone } from "./session";

/** A notable location that matters to the campaign. */
export interface WorldRegion {
  name: string;
  type: string;
  description: string;
  /** Travel map — normalized x in [0, 100]; null/undefined → auto-laid-out. */
  x?: number | null;
  /** Travel map — normalized y in [0, 100]; null/undefined → auto-laid-out. */
  y?: number | null;
  /** Travel map — names of regions this one has a direct route to (undirected). */
  connections?: string[];
}

/** A faction with the three 5E levers: a goal, a resource, and a pressure to act. */
export interface WorldFaction {
  name: string;
  goal: string;
  resource: string;
  pressure: string;
  description: string;
}

/** A key NPC, ideally tied to a place and bound to the party. */
export interface WorldNpc {
  name: string;
  race: string;
  role: string;
  location: string;
  bond: string;
  description: string;
}

/** One weapon-style attack on a custom monster (mirrors the backend MonsterAttack). */
export interface MonsterAttack {
  name: string;
  /** "MELEE" or "RANGED". */
  kind: string;
  toHit: number;
  reach: number | null;
  range: number | null;
  damageDice: string;
  damageType: string;
}

/** {@code count} repeats of the named attack per turn. */
export interface Multiattack {
  count: number;
  attack: string | null;
}

/**
 * A homebrew monster stat block. Mirrors the backend CustomMonster / MonsterTemplate so it can be
 * played in tactical combat. The {@code key} is assigned server-side (namespaced CUSTOM_…), so the
 * client leaves it absent when authoring.
 */
export interface CustomMonster {
  key?: string;
  name: string;
  size: string | null;
  type: string | null;
  cr: number | null;
  ac: number | null;
  hp: number | null;
  hpDice: string | null;
  speed: number | null;
  dexMod: number;
  abilities: Record<string, number>;
  attacks: MonsterAttack[];
  multiattack: Multiattack | null;
}

/** Full read model for a single authored world. */
export interface WorldDto {
  id: string;
  name: string;
  tagline: string | null;
  overview: string | null;
  tone: string | null;
  magicLevel: string | null;
  regions: WorldRegion[];
  factions: WorldFaction[];
  npcs: WorldNpc[];
  customMonsters: CustomMonster[];
  milestones: Milestone[];
  createdAt: string;
  updatedAt: string;
}

/** Lightweight world view for the library list and the "My Worlds" session source. */
export interface WorldSummaryDto {
  id: string;
  name: string;
  tagline: string | null;
  tone: string | null;
  regionCount: number;
  factionCount: number;
  npcCount: number;
  monsterCount: number;
  milestoneCount: number;
  updatedAt: string;
}

/** Context sent with a per-section "Generate with AI" request. */
export interface WorldGenerateRequest {
  name?: string;
  overview?: string;
  tone?: string;
  magicLevel?: string;
  instruction?: string;
}

/** AI-generated draft of the Overview step. */
export interface WorldOverviewSuggestion {
  tagline: string;
  tone: string;
  magicLevel: string;
  overview: string;
}

/** Create/update payload for the World Builder wizard. */
export interface WorldCreateUpdateRequest {
  name: string;
  tagline?: string | null;
  overview?: string | null;
  tone?: string | null;
  magicLevel?: string | null;
  regions: WorldRegion[];
  factions: WorldFaction[];
  npcs: WorldNpc[];
  customMonsters: CustomMonster[];
  milestones: Milestone[];
}
