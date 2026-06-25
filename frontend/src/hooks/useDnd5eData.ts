"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import {
  listRaces,
  getRace,
  listClasses,
  getClass,
  listClassSpells,
  getSpell,
  getEquipment,
  getEquipmentCategory,
  listAlignments,
  kindFromCategory,
  type ApiRef,
  type ApiClass,
  type ApiCountedRef,
  type ApiCategoryChoice,
} from "@/lib/dnd5eapi";
import {
  buildClassEquipment,
  ABILITY_BY_ABBR,
  type RaceInfo,
  type ClassInfo,
  type ClassEquipment,
} from "@/lib/dnd5e";
import type { Spell } from "@/lib/spells";
import type { ItemKind } from "@/types";

/**
 * The 5e catalog is static, so cache it indefinitely. These endpoints are
 * public (no auth token) — unlike the app's own `/api/*` query hooks.
 */
const STATIC = { staleTime: Infinity } as const;

/* ── Races & classes (list + parallel detail fetch) ──────────────── */

export function useRaces() {
  return useQuery({
    queryKey: queryKeys.dnd5e.races,
    ...STATIC,
    queryFn: async (): Promise<RaceInfo[]> => {
      const list = await listRaces();
      const details = await Promise.all(list.results.map((r) => getRace(r.index)));
      return details.map((r) => ({
        index: r.index,
        name: r.name,
        speed: r.speed,
        size: r.size,
        traits: r.traits.map((t) => t.name),
        abilityBonuses: Object.fromEntries(
          r.ability_bonuses.map((b) => [
            ABILITY_BY_ABBR[b.ability_score.index] ?? b.ability_score.index,
            b.bonus,
          ])
        ),
      }));
    },
  });
}

export function useClasses() {
  return useQuery({
    queryKey: queryKeys.dnd5e.classes,
    ...STATIC,
    queryFn: async (): Promise<ClassInfo[]> => {
      const list = await listClasses();
      const details = await Promise.all(list.results.map((c) => getClass(c.index)));
      return details.map((c) => ({
        index: c.index,
        name: c.name,
        hitDie: c.hit_die,
        savingThrows: c.saving_throws.map((s) => s.name),
        proficiencies: c.proficiencies.map((p) => p.name),
      }));
    },
  });
}

export function useAlignments() {
  return useQuery({
    queryKey: queryKeys.dnd5e.alignments,
    ...STATIC,
    queryFn: async (): Promise<string[]> => {
      const list = await listAlignments();
      return list.results.map((a) => a.name);
    },
  });
}

/* ── Spells (level 0 & 1, school + desc via detail) ──────────────── */

export interface ClassSpells {
  cantrips: Spell[];
  level1: Spell[];
}

function firstParagraph(desc: string[]): string {
  const text = desc?.[0] ?? "";
  return text.length > 160 ? `${text.slice(0, 157).trimEnd()}…` : text;
}

export function useClassSpells(classIndex: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.dnd5e.classSpells(classIndex ?? ""),
    enabled: enabled && !!classIndex,
    ...STATIC,
    queryFn: async (): Promise<ClassSpells> => {
      const list = await listClassSpells(classIndex!);
      const refs = list.results.filter((s) => s.level === 0 || s.level === 1);
      // Detail fetch for school + desc; allSettled so a flaky public API still
      // yields a usable picker (failed spells fall back to name + level only).
      const settled = await Promise.allSettled(refs.map((r) => getSpell(r.index)));
      const spells: Spell[] = refs.map((ref, i) => {
        const res = settled[i];
        if (res.status === "fulfilled") {
          return {
            name: res.value.name,
            level: res.value.level,
            school: res.value.school?.name,
            desc: firstParagraph(res.value.desc),
          };
        }
        return { name: ref.name, level: ref.level };
      });
      return {
        cantrips: spells.filter((s) => s.level === 0),
        level1: spells.filter((s) => s.level === 1),
      };
    },
  });
}

/* ── Starting equipment ──────────────────────────────────────────── */

/** Distinct concrete equipment indexes referenced by a class (for kind lookup). */
function collectConcreteIndexes(apiClass: ApiClass): string[] {
  const set = new Set<string>();
  apiClass.starting_equipment.forEach((se) => set.add(se.equipment.index));
  apiClass.starting_equipment_options.forEach((group) => {
    if (group.from.option_set_type !== "options_array") return;
    const visit = (o: ApiCountedRef | ApiCategoryChoice) => {
      if (o.option_type === "counted_reference") set.add(o.of.index);
    };
    group.from.options.forEach((opt) => {
      if (opt.option_type === "multiple") opt.items.forEach(visit);
      else visit(opt);
    });
  });
  return [...set];
}

export function useStartingEquipment(classIndex: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.dnd5e.startingEquipment(classIndex ?? ""),
    enabled: enabled && !!classIndex,
    ...STATIC,
    queryFn: async (): Promise<ClassEquipment> => {
      const apiClass = await getClass(classIndex!);
      const indexes = collectConcreteIndexes(apiClass);
      const settled = await Promise.allSettled(indexes.map((i) => getEquipment(i)));
      const kindMap = new Map<string, ItemKind>();
      indexes.forEach((idx, i) => {
        const res = settled[i];
        if (res.status === "fulfilled") {
          kindMap.set(idx, kindFromCategory(res.value.equipment_category.index));
        }
      });
      const kindOf = (idx: string): ItemKind => kindMap.get(idx) ?? "GEAR";
      return buildClassEquipment(apiClass, kindOf);
    },
  });
}

/** The concrete items of a category — populates a "pick any X" dropdown. */
export function useEquipmentCategory(
  categoryIndex: string | undefined,
  enabled = true
) {
  return useQuery({
    queryKey: queryKeys.dnd5e.equipmentCategory(categoryIndex ?? ""),
    enabled: enabled && !!categoryIndex,
    ...STATIC,
    queryFn: async (): Promise<ApiRef[]> => {
      const cat = await getEquipmentCategory(categoryIndex!);
      return cat.equipment;
    },
  });
}
