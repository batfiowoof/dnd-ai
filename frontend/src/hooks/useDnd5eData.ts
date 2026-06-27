"use client";

import { useQuery } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import {
  listSpecies,
  listBackgrounds,
  listClasses,
  listClassSpells,
  listFeats,
  listEquipment,
  listAlignments,
  parseSpeed,
  kindFromCategory,
  type SrdFeat,
} from "@/lib/dnd5eapi";
import type {
  SpeciesInfo,
  BackgroundInfo,
  ClassInfo,
} from "@/lib/dnd5e";
import type { Spell } from "@/lib/spells";
import type { ItemKind } from "@/types";

/**
 * The SRD catalog is static, so cache it indefinitely. `/api/srd/*` list
 * endpoints already return full records, so each hook maps the array directly —
 * no per-item detail fetch (unlike the old dnd5eapi.co N+1 pattern).
 */
const STATIC = { staleTime: Infinity } as const;

/* ── Species, backgrounds, classes, alignments ───────────────────── */

export function useSpecies() {
  return useQuery({
    queryKey: queryKeys.dnd5e.species,
    ...STATIC,
    queryFn: async (): Promise<SpeciesInfo[]> => {
      const list = await listSpecies();
      return list.map((s) => ({
        index: s.index,
        name: s.name,
        creatureType: s.creatureType,
        size: s.size,
        speed: parseSpeed(s.speed),
        traits: s.traits.map((t) => ({ name: t.name, desc: t.desc })),
      }));
    },
  });
}

export function useBackgrounds() {
  return useQuery({
    queryKey: queryKeys.dnd5e.backgrounds,
    ...STATIC,
    queryFn: async (): Promise<BackgroundInfo[]> => {
      const list = await listBackgrounds();
      return list.map((b) => ({
        index: b.index,
        name: b.name,
        abilityScores: b.abilityScores,
        feat: b.feat,
        skillProficiencies: b.skillProficiencies,
        toolProficiency: b.toolProficiency,
        equipment: { optionA: b.equipment.optionA, optionB: b.equipment.optionB },
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
      return list.map((c) => ({
        index: c.index,
        name: c.name,
        primaryAbility: c.primaryAbility,
        hitDie: c.hitDie,
        savingThrows: c.savingThrows,
        skillProficiencies: {
          choose: c.skillProficiencies?.choose ?? 0,
          from: c.skillProficiencies?.from ?? [],
        },
        weaponProficiencies: c.weaponProficiencies,
        armorTraining: c.armorTraining ?? [],
        startingEquipment: c.startingEquipment ?? "",
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
      return list.map((a) => a.name);
    },
  });
}

/* ── Origin feat detail (for the background feat callout card) ────── */

export function useFeat(featIndex: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.dnd5e.feat(featIndex ?? ""),
    enabled: enabled && !!featIndex,
    ...STATIC,
    retry: 0,
    queryFn: async (): Promise<SrdFeat | null> => {
      const list = await listFeats();
      return list.find((f) => f.index === featIndex) ?? null;
    },
  });
}

/* ── Equipment list (used to classify parsed starting gear) ──────── */

/** A map of SRD equipment index → `ItemKind`, for best-effort gear typing. */
export function useEquipmentKindMap() {
  return useQuery({
    queryKey: queryKeys.dnd5e.equipmentList,
    ...STATIC,
    queryFn: async (): Promise<Map<string, ItemKind>> => {
      const list = await listEquipment();
      const map = new Map<string, ItemKind>();
      for (const e of list) map.set(e.index, kindFromCategory(e.category));
      return map;
    },
  });
}

/* ── Spells (level 0 & 1 for creation) ───────────────────────────── */

export interface ClassSpells {
  cantrips: Spell[];
  level1: Spell[];
}

function firstSentence(desc: string | undefined): string {
  const text = desc ?? "";
  return text.length > 160 ? `${text.slice(0, 157).trimEnd()}…` : text;
}

export function useClassSpells(classIndex: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.dnd5e.classSpells(classIndex ?? ""),
    enabled: enabled && !!classIndex,
    ...STATIC,
    queryFn: async (): Promise<ClassSpells> => {
      // The endpoint returns full spell records (school + desc already present),
      // so we filter to creation-relevant levels client-side — no detail fetch.
      const all = await listClassSpells(classIndex!);
      const spells: Spell[] = all
        .filter((s) => s.level === 0 || s.level === 1)
        .map((s) => ({
          name: s.name,
          level: s.level,
          school: s.school,
          desc: firstSentence(s.desc),
        }));
      return {
        cantrips: spells.filter((s) => s.level === 0),
        level1: spells.filter((s) => s.level === 1),
      };
    },
  });
}
