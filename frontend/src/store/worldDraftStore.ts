import { create } from "zustand";
import {
  INITIAL_DRAFT,
  type WorldDraftData,
  type WorldStep,
} from "@/lib/worldBuilder";

/** Re-export so consumers can grab the draft shape from the store module too. */
export type { WorldDraftData };

interface WorldDraftStore extends WorldDraftData {
  setStep: (step: WorldStep) => void;
  /** Shallow-merge a partial of the editable fields (used by every step + AI fills). */
  setField: (patch: Partial<WorldDraftData>) => void;
  /** Replace the whole draft (hydrate from a saved world in the edit flow). */
  hydrate: (draft: WorldDraftData) => void;
  reset: () => void;
}

export const useWorldDraftStore = create<WorldDraftStore>((set) => ({
  ...INITIAL_DRAFT,

  setStep: (step) => set({ step }),
  setField: (patch) => set(patch),
  hydrate: (draft) => set({ ...draft }),
  reset: () => set({ ...INITIAL_DRAFT }),
}));
