import type { Milestone } from "./session";
import type { InventoryItem } from "./player";

/** A location inside a region the party can move between locally (mirrors the backend WorldSubregion). */
export interface WorldSubregion {
  name: string;
  type: string;
  description: string;
  /** Local mini-map — normalized x in [0, 100]; null/undefined → auto-laid-out. */
  x?: number | null;
  /** Local mini-map — normalized y in [0, 100]; null/undefined → auto-laid-out. */
  y?: number | null;
  /** Local mini-map — names of sibling subregions this one has a direct local path to (undirected). */
  connections?: string[];
}

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
  /** Finer locations inside this region the party can move between locally. */
  subregions?: WorldSubregion[];
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
  /** The region this NPC belongs to (matches a WorldRegion name), or "" if none. */
  region: string;
  /** The subregion within that region they're found in, or "" if none. */
  subregion: string;
  /** Optional finer "specific spot" free-text note (kept for back-compat). */
  location: string;
  bond: string;
  description: string;
  /** Authored starting attitude toward the party as a signed score in [-100, 100] (0 = Neutral). */
  disposition?: number;
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

/** Broad quest category (mirrors the backend QuestType). */
export type QuestType = "MAIN" | "SIDE" | "PERSONAL";

/**
 * Engine-owned quest lifecycle (mirrors the backend QuestStatus). Authored quests start AVAILABLE, or
 * LOCKED when they have prerequisites; the rest is driven by the DM tools during play.
 */
export type QuestStatus = "LOCKED" | "AVAILABLE" | "ACTIVE" | "COMPLETED" | "FAILED";

/** One ordered step of a quest; {@code completed} is engine-owned. */
export interface QuestObjective {
  key: string;
  description: string;
  completed: boolean;
}

/** A real impact applied on completion: nudge a named NPC's disposition by {@code delta} (-100..100). */
export interface QuestDispositionShift {
  npcName: string;
  delta: number;
}

/** What a quest pays out. Coin is just an inventory item (e.g. a GEAR "150 GP"). */
export interface QuestReward {
  description: string;
  items: InventoryItem[];
  /** Key of a milestone to award on completion (levels the party), or null. */
  milestoneKey: string | null;
}

/** An authored quest — objectives, chains, a twist, impacts, and rewards. Mirrors the backend Quest. */
export interface Quest {
  key: string;
  title: string;
  summary: string;
  type: QuestType;
  /** Keys of other quests that must be completed before this one unlocks. */
  prerequisiteKeys: string[];
  objectives: QuestObjective[];
  /** DM-only hidden complication and when to reveal it. */
  twist: string;
  twistTrigger: string;
  reward: QuestReward;
  completionImpact: string;
  failureImpact: string;
  dispositionShifts: QuestDispositionShift[];
  /** Engine-owned; authored quests default to AVAILABLE (or LOCKED with prerequisites). */
  status: QuestStatus;
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
  quests: Quest[];
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
  questCount: number;
  updatedAt: string;
}

/** Context sent with a per-section "Generate with AI" request. */
export interface WorldGenerateRequest {
  name?: string;
  overview?: string;
  tone?: string;
  magicLevel?: string;
  instruction?: string;
  /** Already-authored geography (with subregions) so NPC/subregion generation matches real names. */
  regions?: WorldRegion[];
  /** Already-authored milestones/NPCs/factions so quest generation references real keys and names. */
  milestones?: Milestone[];
  npcs?: WorldNpc[];
  factions?: WorldFaction[];
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
  quests: Quest[];
}
