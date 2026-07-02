import type {
  CustomMonster,
  Milestone,
  MonsterAttack,
  WorldCreateUpdateRequest,
  WorldDto,
  WorldFaction,
  WorldGenerateRequest,
  WorldNpc,
  WorldRegion,
  WorldSubregion,
} from "@/types";

/** Ordered wizard steps. Kept in one place so the progress bar and navigation stay in sync. */
export const WORLD_STEPS = [
  "Overview",
  "Regions",
  "Factions",
  "NPCs",
  "Monsters",
  "Milestones",
  "Review",
] as const;

export type WorldStep = (typeof WORLD_STEPS)[number];

/** Ability keys used on custom monster stat blocks (drive saving throws in combat). */
export const ABILITY_KEYS = ["STR", "DEX", "CON", "INT", "WIS", "CHA"] as const;

export const DEFAULT_ABILITIES: Record<string, number> = {
  STR: 10,
  DEX: 10,
  CON: 10,
  INT: 10,
  WIS: 10,
  CHA: 10,
};

/** The editable shape held by the wizard store (everything the user can author). */
export interface WorldDraftData {
  step: WorldStep;
  name: string;
  tagline: string;
  overview: string;
  tone: string;
  magicLevel: string;
  regions: WorldRegion[];
  factions: WorldFaction[];
  npcs: WorldNpc[];
  customMonsters: CustomMonster[];
  milestones: Milestone[];
}

export const INITIAL_DRAFT: WorldDraftData = {
  step: "Overview",
  name: "",
  tagline: "",
  overview: "",
  tone: "",
  magicLevel: "",
  regions: [],
  factions: [],
  npcs: [],
  customMonsters: [],
  milestones: [],
};

/* ── Empty-item factories (used by "Add" buttons) ────────────── */

export const emptyRegion = (): WorldRegion => ({
  name: "",
  type: "",
  description: "",
  subregions: [],
});

export const emptySubregion = (): WorldSubregion => ({ name: "", type: "", description: "" });

export const emptyFaction = (): WorldFaction => ({
  name: "",
  goal: "",
  resource: "",
  pressure: "",
  description: "",
});

export const emptyNpc = (): WorldNpc => ({
  name: "",
  race: "",
  role: "",
  region: "",
  subregion: "",
  location: "",
  bond: "",
  description: "",
  disposition: 0,
});

export const emptyAttack = (): MonsterAttack => ({
  name: "",
  kind: "MELEE",
  toHit: 4,
  reach: 5,
  range: null,
  damageDice: "1d6",
  damageType: "",
});

export const emptyMonster = (): CustomMonster => ({
  name: "",
  size: "Medium",
  type: "",
  cr: 1,
  ac: 12,
  hp: 11,
  hpDice: "",
  speed: 30,
  dexMod: 0,
  abilities: { ...DEFAULT_ABILITIES },
  attacks: [emptyAttack()],
  multiattack: null,
});

export const emptyMilestone = (): Milestone => ({ key: "", title: "", description: "" });

/** 5E ability modifier: floor((score − 10) / 2). */
export function abilityMod(score: number): number {
  return Math.floor((score - 10) / 2);
}

/** Slugify a title into a stable milestone key (lowercase, dashed). */
export function slugify(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

/** Hydrate the editable draft from a saved world (edit flow). */
export function worldToDraft(world: WorldDto): WorldDraftData {
  return {
    step: "Overview",
    name: world.name ?? "",
    tagline: world.tagline ?? "",
    overview: world.overview ?? "",
    tone: world.tone ?? "",
    magicLevel: world.magicLevel ?? "",
    regions: (world.regions ?? []).map((r) => ({ ...r, subregions: r.subregions ?? [] })),
    factions: world.factions ?? [],
    // Back-fill structured location + disposition fields for worlds authored before they existed.
    npcs: (world.npcs ?? []).map((n) => ({
      ...n,
      region: n.region ?? "",
      subregion: n.subregion ?? "",
      location: n.location ?? "",
      disposition: n.disposition ?? 0,
    })),
    customMonsters: (world.customMonsters ?? []).map((m) => ({
      ...m,
      abilities: { ...DEFAULT_ABILITIES, ...(m.abilities ?? {}) },
      attacks: m.attacks ?? [],
    })),
    milestones: world.milestones ?? [],
  };
}

/**
 * Compile the draft into the API request. Derives each custom monster's {@code dexMod} from its DEX
 * score and fills milestone keys from their titles when the author left them blank (the backend drops
 * milestones without a key). The server does the authoritative cleaning; this just shapes the payload.
 */
export function draftToRequest(draft: WorldDraftData): WorldCreateUpdateRequest {
  return {
    name: draft.name.trim(),
    tagline: draft.tagline.trim() || null,
    overview: draft.overview.trim() || null,
    tone: draft.tone.trim() || null,
    magicLevel: draft.magicLevel.trim() || null,
    regions: draft.regions,
    factions: draft.factions,
    npcs: draft.npcs,
    customMonsters: draft.customMonsters.map((m) => ({
      ...m,
      dexMod: abilityMod(m.abilities?.DEX ?? 10),
    })),
    milestones: draft.milestones.map((m) => ({
      ...m,
      key: m.key.trim() || slugify(m.title),
    })),
  };
}

/** Build the grounding context an AI generate call sends from the current draft. */
export function draftToContext(
  draft: WorldDraftData,
  instruction?: string
): WorldGenerateRequest {
  return {
    name: draft.name.trim() || undefined,
    overview: draft.overview.trim() || undefined,
    tone: draft.tone.trim() || undefined,
    magicLevel: draft.magicLevel.trim() || undefined,
    instruction: instruction?.trim() || undefined,
    // Ground geography-aware sections (NPCs, subregions) on the regions authored so far.
    regions: draft.regions.length > 0 ? draft.regions : undefined,
  };
}

/** Only the Overview step gates progress — a world needs a name; the rest are optional. */
export function canProceed(step: WorldStep, draft: WorldDraftData): boolean {
  if (step === "Overview") return draft.name.trim().length > 0;
  return true;
}
