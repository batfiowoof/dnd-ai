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
  dnd5e: {
    races: ["dnd5e", "races"] as const,
    classes: ["dnd5e", "classes"] as const,
    alignments: ["dnd5e", "alignments"] as const,
    classSpells: (classIndex: string) =>
      ["dnd5e", "classSpells", classIndex] as const,
    startingEquipment: (classIndex: string) =>
      ["dnd5e", "startingEquipment", classIndex] as const,
    equipmentCategory: (categoryIndex: string) =>
      ["dnd5e", "equipmentCategory", categoryIndex] as const,
    spellByName: (name: string) => ["dnd5e", "spellByName", name] as const,
    equipmentByName: (name: string) =>
      ["dnd5e", "equipmentByName", name] as const,
  },
} as const;
