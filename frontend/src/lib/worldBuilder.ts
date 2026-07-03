import type {
  CustomMonster,
  Milestone,
  MonsterAttack,
  Quest,
  QuestObjective,
  Shop,
  ShopStockEntry,
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
  "Quests",
  "Shops",
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
  quests: Quest[];
  shops: Shop[];
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
  quests: [],
  shops: [],
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

export const emptyStockEntry = (): ShopStockEntry => ({
  srdIndex: null,
  name: "",
  kind: "GEAR",
  basePriceCopper: 0,
  quantity: -1,
});

export const emptyShop = (): Shop => ({
  key: "",
  name: "",
  type: "GENERAL",
  description: "",
  region: "",
  subregion: "",
  economyFactor: 1.0,
  ownerNpcName: "",
  stock: [],
});

export const emptyObjective = (): QuestObjective => ({
  key: "",
  description: "",
  completed: false,
});

export const emptyQuest = (): Quest => ({
  key: "",
  title: "",
  summary: "",
  type: "SIDE",
  prerequisiteKeys: [],
  objectives: [],
  twist: "",
  twistTrigger: "",
  reward: { description: "", items: [], milestoneKey: null },
  completionImpact: "",
  failureImpact: "",
  dispositionShifts: [],
  status: "AVAILABLE",
});

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
    // Back-fill quest sub-structures so older/partial payloads render without crashing the editor.
    quests: (world.quests ?? []).map((q) => ({
      ...q,
      prerequisiteKeys: q.prerequisiteKeys ?? [],
      objectives: q.objectives ?? [],
      dispositionShifts: q.dispositionShifts ?? [],
      reward: {
        description: q.reward?.description ?? "",
        items: q.reward?.items ?? [],
        milestoneKey: q.reward?.milestoneKey ?? null,
      },
    })),
    // Back-fill shop sub-structures so older/partial payloads render without crashing the editor.
    shops: (world.shops ?? []).map((s) => ({
      ...s,
      region: s.region ?? "",
      subregion: s.subregion ?? "",
      ownerNpcName: s.ownerNpcName ?? "",
      economyFactor: s.economyFactor ?? 1.0,
      stock: s.stock ?? [],
    })),
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
    // Backfill quest + objective keys from their titles/descriptions when left blank (server drops
    // keyless quests). The server does the authoritative cleaning; this just shapes the payload.
    quests: draft.quests.map((q) => ({
      ...q,
      key: q.key.trim() || slugify(q.title),
      objectives: q.objectives.map((o) => ({
        ...o,
        key: o.key.trim() || slugify(o.description),
      })),
    })),
    // Backfill shop keys from names when blank (server drops keyless shops).
    shops: draft.shops.map((s) => ({
      ...s,
      key: s.key.trim() || slugify(s.name),
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
    // Ground quest generation on the milestones, NPCs, and factions authored so far so the AI links
    // real keys/names. Harmless for other sections, which ignore these fields.
    milestones: draft.milestones.length > 0 ? draft.milestones : undefined,
    npcs: draft.npcs.length > 0 ? draft.npcs : undefined,
    factions: draft.factions.length > 0 ? draft.factions : undefined,
  };
}

/** Only the Overview step gates progress — a world needs a name; the rest are optional. */
export function canProceed(step: WorldStep, draft: WorldDraftData): boolean {
  if (step === "Overview") return draft.name.trim().length > 0;
  return true;
}
