import { create } from "zustand";
import {
  EMPTY_ASI,
  type AbilityName,
  type AsiAssignment,
  type ClassInfo,
  type BackgroundInfo,
  type SpeciesInfo,
} from "@/lib/dnd5e";
import { SPELLCASTING } from "@/lib/spells";
import type {
  Step,
  AbilityMethod,
  CharacterDraftData,
} from "@/lib/characterCreation";

/** Re-export so consumers can grab the draft shape from the store module too. */
export type { CharacterDraftData };

interface CharacterDraftStore extends CharacterDraftData {
  /* plain value setters */
  setStep: (step: Step) => void;
  setName: (name: string) => void;
  setAlignment: (alignment: string) => void;
  setBackstory: (backstory: string) => void;
  setImageUrl: (imageUrl: string) => void;
  setSelectedSpecies: (sp: SpeciesInfo | null) => void;
  setAbilityMethod: (method: AbilityMethod) => void;
  setAsi: (asi: AsiAssignment) => void;
  setClassEquipLetter: (letter: string) => void;
  setBgEquipLetter: (letter: "A" | "B") => void;

  /* useState-style functional setters (call sites pass `(prev) => next`) */
  setBaseAbilities: (
    updater: (prev: Record<AbilityName, number>) => Record<AbilityName, number>
  ) => void;
  setStandardAssignments: (
    updater: (
      prev: Record<AbilityName, number | null>
    ) => Record<AbilityName, number | null>
  ) => void;

  /* setters that also fire the class/background-change RESET effects */
  setSelectedClass: (cls: ClassInfo | null) => void;
  setSelectedBackground: (bg: BackgroundInfo | null) => void;

  /* capped toggles */
  toggleClassSkill: (skill: string) => void;
  toggleCantrip: (name: string) => void;
  toggleSpell: (name: string) => void;

  reset: () => void;
}

const initialState: CharacterDraftData = {
  step: "Class",
  name: "",
  selectedClass: null,
  selectedBackground: null,
  selectedSpecies: null,
  alignment: "",
  backstory: "",
  imageUrl: "",
  classSkills: [],
  abilityMethod: "standard",
  baseAbilities: {
    strength: 10,
    dexterity: 10,
    constitution: 10,
    intelligence: 10,
    wisdom: 10,
    charisma: 10,
  },
  standardAssignments: {
    strength: null,
    dexterity: null,
    constitution: null,
    intelligence: null,
    wisdom: null,
    charisma: null,
  },
  asi: EMPTY_ASI,
  classEquipLetter: "A",
  bgEquipLetter: "A",
  selectedCantrips: [],
  selectedSpells: [],
};

export const useCharacterDraftStore = create<CharacterDraftStore>((set) => ({
  ...initialState,

  setStep: (step) => set({ step }),
  setName: (name) => set({ name }),
  setAlignment: (alignment) => set({ alignment }),
  setBackstory: (backstory) => set({ backstory }),
  setImageUrl: (imageUrl) => set({ imageUrl }),
  setSelectedSpecies: (selectedSpecies) => set({ selectedSpecies }),
  setAbilityMethod: (abilityMethod) => set({ abilityMethod }),
  setAsi: (asi) => set({ asi }),
  setClassEquipLetter: (classEquipLetter) => set({ classEquipLetter }),
  setBgEquipLetter: (bgEquipLetter) => set({ bgEquipLetter }),

  setBaseAbilities: (updater) =>
    set((s) => ({ baseAbilities: updater(s.baseAbilities) })),
  setStandardAssignments: (updater) =>
    set((s) => ({ standardAssignments: updater(s.standardAssignments) })),

  // Reset class-dependent choices when the class actually changes (index guard
  // mirrors the original `useEffect([selectedClass?.index])`).
  setSelectedClass: (cls) =>
    set((s) => {
      if (s.selectedClass?.index === cls?.index) return { selectedClass: cls };
      return {
        selectedClass: cls,
        classSkills: [],
        classEquipLetter: "A",
        selectedCantrips: [],
        selectedSpells: [],
      };
    }),

  // Reset the ASI split + background equipment when the background actually
  // changes (mirrors the original `useEffect([selectedBackground?.index])`).
  setSelectedBackground: (bg) =>
    set((s) => {
      if (s.selectedBackground?.index === bg?.index)
        return { selectedBackground: bg };
      return { selectedBackground: bg, asi: EMPTY_ASI, bgEquipLetter: "A" };
    }),

  toggleClassSkill: (skill) =>
    set((s) => {
      if (!s.selectedClass) return {};
      const prev = s.classSkills;
      if (prev.includes(skill))
        return { classSkills: prev.filter((x) => x !== skill) };
      if (prev.length >= s.selectedClass.skillProficiencies.choose) return {};
      return { classSkills: [...prev, skill] };
    }),

  toggleCantrip: (name) =>
    set((s) => {
      const caps = s.selectedClass
        ? SPELLCASTING[s.selectedClass.name]
        : undefined;
      const prev = s.selectedCantrips;
      if (prev.includes(name))
        return { selectedCantrips: prev.filter((x) => x !== name) };
      if (!caps || prev.length >= caps.cantripsKnown) return {};
      return { selectedCantrips: [...prev, name] };
    }),

  toggleSpell: (name) =>
    set((s) => {
      const caps = s.selectedClass
        ? SPELLCASTING[s.selectedClass.name]
        : undefined;
      const prev = s.selectedSpells;
      if (prev.includes(name))
        return { selectedSpells: prev.filter((x) => x !== name) };
      if (!caps || prev.length >= caps.spellsKnown) return {};
      return { selectedSpells: [...prev, name] };
    }),

  reset: () => set({ ...initialState }),
}));
