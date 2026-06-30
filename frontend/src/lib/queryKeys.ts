/**
 * Centralized React Query key factory.
 * Keeps cache keys consistent across query hooks and invalidations.
 */
export const queryKeys = {
  session: {
    mine: () => ["sessions", "mine"] as const,
    state: (sessionId: string) => ["session", sessionId, "state"] as const,
    history: (sessionId: string) => ["session", sessionId, "history"] as const,
    players: (sessionId: string) => ["session", sessionId, "players"] as const,
    states: (sessionId: string) => ["session", sessionId, "states"] as const,
    combat: (sessionId: string) => ["session", sessionId, "combat"] as const,
  },
  characters: {
    all: ["characters"] as const,
    byId: (id: string) => ["characters", id] as const,
  },
  worlds: {
    all: ["worlds"] as const,
    byId: (id: string) => ["worlds", id] as const,
  },
  dnd5e: {
    species: ["dnd5e", "species"] as const,
    backgrounds: ["dnd5e", "backgrounds"] as const,
    classes: ["dnd5e", "classes"] as const,
    alignments: ["dnd5e", "alignments"] as const,
    equipmentList: ["dnd5e", "equipmentList"] as const,
    feat: (index: string) => ["dnd5e", "feat", index] as const,
    classSpells: (classIndex: string) =>
      ["dnd5e", "classSpells", classIndex] as const,
    spellByName: (name: string) => ["dnd5e", "spellByName", name] as const,
    equipmentByName: (name: string) =>
      ["dnd5e", "equipmentByName", name] as const,
  },
} as const;
