/** D&D 5e overland travel pace. Mirrors the backend `TravelPace` enum. */
export type TravelPace = "FAST" | "NORMAL" | "SLOW";

/** One location on the travel map: authored flavour plus its resolved node-graph position. */
export interface RegionNode {
  name: string;
  type: string;
  description: string;
  /** Normalized map coordinates in [0, 100]. */
  x: number;
  y: number;
  /** Names of regions directly reachable from here by a route. */
  connections: string[];
}

/** Travel-map read model for a session (empty `regions` → the session has no authored world). */
export interface TravelMapDto {
  regions: RegionNode[];
  /** The party's current location name, or null if not yet placed. */
  currentRegion: string | null;
  /** Elapsed in-game time in minutes (Day N • HH:MM). */
  inGameMinutes: number;
  /** The last overland pace chosen. */
  pace: TravelPace;
}

/** WebSocket event broadcast when the party arrives somewhere new. */
export interface LocationChangedEvent {
  type: "LOCATION_CHANGED";
  sessionId: string;
  currentRegion: string;
  fromRegion: string;
  inGameMinutes: number;
  pace: TravelPace;
}
