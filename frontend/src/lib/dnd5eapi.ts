/* ── dnd5eapi.co REST client (SRD 5.1, 2014 ruleset) ──────────────
 * Public, no-auth, CORS-enabled (`access-control-allow-origin: *`). Used at
 * character-creation time to source races, classes, spells, and equipment.
 * This module is framework-free — React Query hooks live in
 * `hooks/useDnd5eData.ts`, which maps these raw shapes to the wizard's UI models.
 */

import type { ItemKind } from "@/types";

const DND_API = "https://www.dnd5eapi.co/api/2014";

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${DND_API}${path}`);
  if (!res.ok) {
    throw new Error(`dnd5eapi ${path} failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

/* ── Raw API response shapes ─────────────────────────────────────── */

export interface ApiRef {
  index: string;
  name: string;
  url: string;
}

export interface ApiList<T> {
  count: number;
  results: T[];
}

export interface ApiRace {
  index: string;
  name: string;
  speed: number;
  size: string;
  ability_bonuses: { ability_score: ApiRef; bonus: number }[];
  traits: ApiRef[];
}

export interface ApiClass {
  index: string;
  name: string;
  hit_die: number;
  proficiencies: ApiRef[];
  saving_throws: ApiRef[];
  starting_equipment: { equipment: ApiRef; quantity: number }[];
  starting_equipment_options: ApiEquipOptionGroup[];
  spellcasting?: { level: number };
}

export interface ApiSpellRef {
  index: string;
  name: string;
  level: number;
  url: string;
}

export interface ApiSpell {
  index: string;
  name: string;
  level: number;
  school: ApiRef;
  desc: string[];
}

export interface ApiEquipment {
  index: string;
  name: string;
  equipment_category: ApiRef;
}

export interface ApiEquipCategory {
  index: string;
  name: string;
  equipment: ApiRef[];
}

/* starting_equipment_options is a small tagged union (see /classes/{idx}). */

export interface ApiCountedRef {
  option_type: "counted_reference";
  count: number;
  of: ApiRef;
}

/** "choose N from an equipment category" (e.g. any martial weapon). */
export interface ApiCategoryChoice {
  option_type: "choice";
  choice: {
    desc: string;
    choose: number;
    type: string;
    from: {
      option_set_type: "equipment_category";
      equipment_category: ApiRef;
    };
  };
}

/** A bundle resolved together (e.g. leather armor + longbow + 20 arrows). */
export interface ApiMultiple {
  option_type: "multiple";
  items: Array<ApiCountedRef | ApiCategoryChoice>;
}

export type ApiEquipOption = ApiCountedRef | ApiCategoryChoice | ApiMultiple;

export interface ApiEquipOptionGroup {
  desc: string;
  choose: number;
  type: string;
  from:
    | { option_set_type: "options_array"; options: ApiEquipOption[] }
    | { option_set_type: "equipment_category"; equipment_category: ApiRef };
}

/* ── Endpoints ───────────────────────────────────────────────────── */

export const listClasses = () => apiGet<ApiList<ApiRef>>("/classes");
export const getClass = (index: string) => apiGet<ApiClass>(`/classes/${index}`);
export const listClassSpells = (index: string) =>
  apiGet<ApiList<ApiSpellRef>>(`/classes/${index}/spells`);
export const getSpell = (index: string) => apiGet<ApiSpell>(`/spells/${index}`);
export const listRaces = () => apiGet<ApiList<ApiRef>>("/races");
export const getRace = (index: string) => apiGet<ApiRace>(`/races/${index}`);
export const getEquipment = (index: string) =>
  apiGet<ApiEquipment>(`/equipment/${index}`);
export const getEquipmentCategory = (index: string) =>
  apiGet<ApiEquipCategory>(`/equipment-categories/${index}`);
export const listAlignments = () => apiGet<ApiList<ApiRef>>("/alignments");

/* ── Mappers ─────────────────────────────────────────────────────── */

/**
 * Map an equipment-category index to our inventory `ItemKind`. Works for both
 * concrete-item categories (literal `"weapon"`/`"armor"`) and the broader
 * category-pick indexes (`"martial-weapons"`, `"light-armor"`, …). Anything that
 * isn't a weapon/armor/shield is generic `GEAR`. Potions/scrolls don't appear in
 * starting gear (the backend seeds the two healing potions on join).
 */
export function kindFromCategory(categoryIndex: string): ItemKind {
  const lc = categoryIndex.toLowerCase();
  if (lc.includes("armor") || lc.includes("shield")) return "ARMOR";
  if (lc.includes("weapon")) return "WEAPON";
  return "GEAR";
}
