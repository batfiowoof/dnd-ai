/**
 * Centralized React Query key factory.
 * Keeps cache keys consistent across query hooks and invalidations.
 */
export const queryKeys = {
  session: {
    state: (sessionId: string) => ["session", sessionId, "state"] as const,
    history: (sessionId: string) => ["session", sessionId, "history"] as const,
    players: (sessionId: string) => ["session", sessionId, "players"] as const,
  },
  characters: {
    all: ["characters"] as const,
    byId: (id: string) => ["characters", id] as const,
  },
} as const;
