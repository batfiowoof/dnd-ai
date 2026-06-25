/* ── D&D 5E rules & helpers for character creation ────────────────
 * Reference *content* (races, classes, spells, equipment) is sourced from
 * dnd5eapi.co via `hooks/useDnd5eData.ts`. This module holds only rules math,
 * the UI model shapes those hooks produce, and the equipment build/resolve logic.
 * `BACKGROUNDS` stays local: the 2014 API exposes only one background.
 */

import type { ItemKind, InventoryItem } from "@/types";
import {
  kindFromCategory,
  type ApiClass,
  type ApiEquipOption,
  type ApiCountedRef,
  type ApiCategoryChoice,
} from "@/lib/dnd5eapi";

/* ── UI models produced by the data hooks ────────────────────────── */

export interface RaceInfo {
  index: string;
  name: string;
  /** Keyed by full ability name (e.g. "dexterity"), mapped from API abbreviations. */
  abilityBonuses: Record<string, number>;
  speed: number;
  traits: string[];
  size: string;
}

export interface ClassInfo {
  index: string;
  name: string;
  hitDie: number;
  savingThrows: string[];
  proficiencies: string[];
}

/* ── Static rules data (no SRD content) ──────────────────────────── */

/** Background is a non-mechanical display string; the API has only "acolyte". */
export const BACKGROUNDS = [
  "Acolyte",
  "Charlatan",
  "Criminal",
  "Entertainer",
  "Folk Hero",
  "Guild Artisan",
  "Hermit",
  "Noble",
  "Outlander",
  "Sage",
  "Sailor",
  "Soldier",
  "Urchin",
];

export const STANDARD_ARRAY = [15, 14, 13, 12, 10, 8];

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

/** Full ability name keyed by the API's 3-letter `ability_score.index`. */
export const ABILITY_BY_ABBR: Record<string, AbilityName> = {
  str: "strength",
  dex: "dexterity",
  con: "constitution",
  int: "intelligence",
  wis: "wisdom",
  cha: "charisma",
};

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

/* ── Starting equipment (built from the API class payload) ───────── */

export interface ResolvedItem {
  name: string;
  qty: number;
  kind: ItemKind;
}

/** A "pick one (or N) specific item from this category" requirement. */
export interface CategoryPick {
  categoryIndex: string;
  categoryName: string;
  count: number;
}

/** One selectable option within a choice group (the "(a)" / "(b)" of a slot). */
export interface EquipOption {
  label: string;
  items: ResolvedItem[];
  /** Category sub-picks the player must resolve if this option is chosen. */
  categoryPicks: CategoryPick[];
}

/** A single equipment slot the player resolves by picking one option. */
export interface EquipGroup {
  prompt: string;
  options: EquipOption[];
}

export interface ClassEquipment {
  fixed: ResolvedItem[];
  groups: EquipGroup[];
}

function itemLabel(name: string, qty: number): string {
  return qty > 1 ? `${name} ×${qty}` : name;
}

function buildOption(
  opt: ApiEquipOption,
  kindOf: (index: string) => ItemKind
): EquipOption {
  const items: ResolvedItem[] = [];
  const categoryPicks: CategoryPick[] = [];
  const labels: string[] = [];

  const consume = (o: ApiCountedRef | ApiCategoryChoice) => {
    if (o.option_type === "counted_reference") {
      items.push({ name: o.of.name, qty: o.count, kind: kindOf(o.of.index) });
      labels.push(itemLabel(o.of.name, o.count));
    } else {
      const ec = o.choice.from.equipment_category;
      categoryPicks.push({
        categoryIndex: ec.index,
        categoryName: ec.name,
        count: o.choice.choose,
      });
      labels.push(
        o.choice.choose > 1 ? `${o.choice.choose}× any ${ec.name}` : `Any ${ec.name}`
      );
    }
  };

  if (opt.option_type === "multiple") opt.items.forEach(consume);
  else consume(opt);

  return { label: labels.join(" + "), items, categoryPicks };
}

/**
 * Translate an API class payload into the choice model the wizard renders.
 * `kindOf` resolves a concrete item index to an `ItemKind` (via fetched item
 * details); category-pick kinds are derived from the category index at resolve.
 */
export function buildClassEquipment(
  apiClass: ApiClass,
  kindOf: (index: string) => ItemKind
): ClassEquipment {
  const fixed: ResolvedItem[] = apiClass.starting_equipment.map((se) => ({
    name: se.equipment.name,
    qty: se.quantity,
    kind: kindOf(se.equipment.index),
  }));

  const groups: EquipGroup[] = apiClass.starting_equipment_options.map((group) => {
    if (group.from.option_set_type === "equipment_category") {
      const ec = group.from.equipment_category;
      return {
        prompt: group.desc,
        options: [
          {
            label: `Any ${ec.name}`,
            items: [],
            categoryPicks: [
              { categoryIndex: ec.index, categoryName: ec.name, count: group.choose },
            ],
          },
        ],
      };
    }
    return {
      prompt: group.desc,
      options: group.from.options.map((opt) => buildOption(opt, kindOf)),
    };
  });

  return { fixed, groups };
}

/** Stable key for one category-pick slot, shared by the wizard and the resolver. */
export function catKey(groupIndex: number, pickIndex: number, slot: number): string {
  return `${groupIndex}-${pickIndex}-${slot}`;
}

/**
 * Merge fixed gear with the chosen option (and any resolved category picks) of
 * each group into a stacked item list. `selections[i]` is the chosen option index
 * for `groups[i]`; `categoryChoices[catKey(...)]` is the chosen item name.
 */
export function resolveEquipment(
  classEquip: ClassEquipment,
  selections: number[],
  categoryChoices: Record<string, string>
): InventoryItem[] {
  const picked: ResolvedItem[] = [...classEquip.fixed];

  classEquip.groups.forEach((group, gi) => {
    const opt = group.options[selections[gi] ?? 0];
    if (!opt) return;
    picked.push(...opt.items);
    opt.categoryPicks.forEach((pick, pi) => {
      for (let slot = 0; slot < pick.count; slot++) {
        const name = categoryChoices[catKey(gi, pi, slot)];
        if (name) {
          picked.push({ name, qty: 1, kind: kindFromCategory(pick.categoryIndex) });
        }
      }
    });
  });

  const merged: InventoryItem[] = [];
  for (const it of picked) {
    const existing = merged.find((m) => m.name === it.name && m.kind === it.kind);
    if (existing) existing.qty += it.qty;
    else merged.push({ name: it.name, qty: it.qty, kind: it.kind, equipped: false });
  }
  return merged;
}

/** True when every category sub-pick of the selected options has been filled. */
export function equipmentSelectionComplete(
  classEquip: ClassEquipment,
  selections: number[],
  categoryChoices: Record<string, string>
): boolean {
  return classEquip.groups.every((group, gi) => {
    const opt = group.options[selections[gi] ?? 0];
    if (!opt) return true;
    return opt.categoryPicks.every((pick, pi) =>
      Array.from({ length: pick.count }).every(
        (_, slot) => !!categoryChoices[catKey(gi, pi, slot)]
      )
    );
  });
}

/** Human-readable strings for the legacy `equipment` field / display. */
export function equipmentToStrings(items: InventoryItem[]): string[] {
  return items.map((i) => (i.qty > 1 ? `${i.name} (${i.qty})` : i.name));
}
