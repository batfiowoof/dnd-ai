/**
 * Character-creation wizard: types, constants, and pure derived calculations.
 *
 * Everything here is framework-free — the functions take the wizard `draft`
 * (+ fetched reference data where needed) and return values. The store holds raw
 * state; the page/steps compute derived values by calling these.
 */
import {
  ABILITY_NAMES,
  ABILITY_LABELS,
  POINT_BUY_COSTS,
  POINT_BUY_TOTAL,
  backgroundTargets,
  backgroundBonuses,
  asiValid,
  parseClassEquipmentOptions,
  parseEquipmentItems,
  calculateHitPoints,
  calculateArmorClass,
  type AbilityName,
  type AsiAssignment,
  type EquipOption,
  type ClassInfo,
  type BackgroundInfo,
  type SpeciesInfo,
} from "@/lib/dnd5e";
import { SPELLCASTING, isCaster } from "@/lib/spells";
import type { ItemKind, InventoryItem } from "@/types";

/* ── Types ───────────────────────────────────────────────────────── */

export type AbilityMethod = "standard" | "pointbuy";

/* ── Step navigation (2024-canonical order) ──────────────────────── */
// Spells only appears for caster classes (derived from the chosen class).
export type Step =
  | "Class"
  | "Background"
  | "Species"
  | "Abilities"
  | "Spells"
  | "Equipment"
  | "Details"
  | "Review";

/**
 * The full wizard draft shape (state, no actions). The Zustand store extends this
 * with setters; the pure functions below accept it (or a structural superset).
 */
export interface CharacterDraftData {
  step: Step;
  name: string;
  selectedClass: ClassInfo | null;
  selectedBackground: BackgroundInfo | null;
  selectedSpecies: SpeciesInfo | null;
  alignment: string;
  backstory: string;
  imageUrl: string;
  classSkills: string[];
  /** Trained skills the class doubles to Expertise (must be a subset of `classSkills`). */
  classExpertise: string[];
  abilityMethod: AbilityMethod;
  baseAbilities: Record<AbilityName, number>;
  standardAssignments: Record<AbilityName, number | null>;
  asi: AsiAssignment;
  classEquipLetter: string;
  bgEquipLetter: "A" | "B";
  selectedCantrips: string[];
  selectedSpells: string[];
}

/* ── Constants ───────────────────────────────────────────────────── */

// Canonical ability-abbreviation map lives in lib/dnd5e; re-exported so wizard
// step components can keep importing it from here.
export { ABILITY_LABELS };

export const ABILITY_DESCRIPTIONS: Record<AbilityName, string> = {
  strength: "Physical power. Melee attacks, carrying capacity, Athletics.",
  dexterity: "Agility and reflexes. AC, ranged attacks, initiative, Stealth.",
  constitution: "Endurance and health. Hit points and CON saves.",
  intelligence: "Learning and reasoning. Arcana, History, Investigation.",
  wisdom: "Perception and insight. Perception, Insight, Medicine, Survival.",
  charisma: "Force of personality. Deception, Persuasion, Performance.",
};

/* ── Caster gating (drives whether the Spells step exists) ───────── */

export function isCasterClass(selectedClass: ClassInfo | null): boolean {
  return selectedClass ? isCaster(selectedClass.name) : false;
}

export function spellCapsFor(selectedClass: ClassInfo | null) {
  return selectedClass ? SPELLCASTING[selectedClass.name] : undefined;
}

/** Ordered step list — Spells appears only for caster classes. */
export function wizardSteps(caster: boolean): Step[] {
  const base: Step[] = ["Class", "Background", "Species", "Abilities"];
  if (caster) base.push("Spells");
  base.push("Equipment", "Details", "Review");
  return base;
}

/* ── Background ability-score increase ───────────────────────────── */

export function bgTargets(draft: CharacterDraftData): AbilityName[] {
  return backgroundTargets(draft.selectedBackground);
}

export function bgBonuses(draft: CharacterDraftData): Record<AbilityName, number> {
  return backgroundBonuses(draft.selectedBackground, draft.asi);
}

/* ── Ability scores (base + final) ───────────────────────────────── */

/** The chosen base score for each ability (before the background bonus). */
export function baseScore(draft: CharacterDraftData): Record<AbilityName, number> {
  return Object.fromEntries(
    ABILITY_NAMES.map((a) => [
      a,
      draft.abilityMethod === "standard"
        ? draft.standardAssignments[a] ?? 8
        : draft.baseAbilities[a],
    ])
  ) as Record<AbilityName, number>;
}

/** Final abilities = chosen base + background ASI bonus. */
export function finalAbilities(
  draft: CharacterDraftData
): Record<AbilityName, number> {
  const base =
    draft.abilityMethod === "standard"
      ? (Object.fromEntries(
          ABILITY_NAMES.map((a) => [a, draft.standardAssignments[a] ?? 8])
        ) as Record<AbilityName, number>)
      : { ...draft.baseAbilities };
  const bonuses = bgBonuses(draft);
  for (const a of ABILITY_NAMES) base[a] += bonuses[a];
  return base;
}

export function pointBuySpent(draft: CharacterDraftData): number {
  if (draft.abilityMethod !== "pointbuy") return 0;
  return ABILITY_NAMES.reduce(
    (sum, a) => sum + (POINT_BUY_COSTS[draft.baseAbilities[a]] ?? 0),
    0
  );
}

export function usedStandardValues(draft: CharacterDraftData): number[] {
  return Object.values(draft.standardAssignments).filter(
    (v) => v !== null
  ) as number[];
}

/* ── Derived combat stats ────────────────────────────────────────── */

export function derivedHP(draft: CharacterDraftData): number {
  if (!draft.selectedClass) return 10;
  return calculateHitPoints(
    draft.selectedClass.hitDie,
    finalAbilities(draft).constitution
  );
}

export function derivedAC(draft: CharacterDraftData): number {
  return calculateArmorClass(finalAbilities(draft).dexterity);
}

export function derivedSpeed(draft: CharacterDraftData): number {
  return draft.selectedSpecies?.speed ?? 30;
}

/* ── Equipment resolution (best-effort parse of SRD free text) ───── */

export function classEquipOptions(draft: CharacterDraftData): EquipOption[] {
  return draft.selectedClass
    ? parseClassEquipmentOptions(draft.selectedClass.startingEquipment)
    : [];
}

export function resolvedItems(
  draft: CharacterDraftData,
  kindMap?: Map<string, ItemKind>
): InventoryItem[] {
  const options = classEquipOptions(draft);
  const classRaw =
    options.find((o) => o.letter === draft.classEquipLetter)?.raw ??
    options[0]?.raw ??
    "";
  const bgRaw =
    draft.bgEquipLetter === "A"
      ? draft.selectedBackground?.equipment.optionA ?? ""
      : draft.selectedBackground?.equipment.optionB ?? "";
  const combined = [classRaw, bgRaw].filter(Boolean).join(", ");
  return combined ? parseEquipmentItems(combined, kindMap) : [];
}

/* ── Validation / step gating ────────────────────────────────────── */

export function baseAbilitiesValid(draft: CharacterDraftData): boolean {
  return draft.abilityMethod === "standard"
    ? ABILITY_NAMES.every((a) => draft.standardAssignments[a] !== null)
    : pointBuySpent(draft) <= POINT_BUY_TOTAL;
}

export function canProceed(step: Step, draft: CharacterDraftData): boolean {
  switch (step) {
    case "Class":
      return (
        !!draft.selectedClass &&
        draft.classSkills.length === draft.selectedClass.skillProficiencies.choose
      );
    case "Background":
      return !!draft.selectedBackground;
    case "Species":
      return !!draft.selectedSpecies;
    case "Abilities":
      return (
        baseAbilitiesValid(draft) && asiValid(draft.selectedBackground, draft.asi)
      );
    case "Spells": {
      const caps = spellCapsFor(draft.selectedClass);
      return (
        !!caps &&
        draft.selectedCantrips.length === caps.cantripsKnown &&
        draft.selectedSpells.length === caps.spellsKnown
      );
    }
    case "Equipment":
      return !!draft.selectedClass && !!draft.selectedBackground;
    case "Details":
      return !!draft.name.trim();
    case "Review":
      return true;
    default:
      return true;
  }
}
