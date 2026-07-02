"use client";

import { useEffect, useState } from "react";
import type { RegionNode, TravelMapDto, TravelPace } from "@/types";
import { useSessionStore } from "@/store/sessionStore";
import TravelMap from "./TravelMap";
import TravelConfirmModal from "./TravelConfirmModal";

interface TravelPanelProps {
  map: TravelMapDto | undefined;
  connected: boolean;
  /** Fire the travel action (the parent also flips the store's `traveling` flag). */
  onTravel: (destinationRegion: string, pace: TravelPace) => void;
}

const norm = (s: string) => s.trim().toLowerCase();

/**
 * The out-of-combat map pane: frames the campaign {@link TravelMap}, tracks the pick→confirm flow,
 * and dispatches travel. Lives in the left pane of the game screen where the combat battle-map
 * otherwise sits. Reads the party's live position and travel state from the store.
 */
export default function TravelPanel({ map, connected, onTravel }: TravelPanelProps) {
  const currentRegion = useSessionStore((s) => s.currentRegion);
  const traveling = useSessionStore((s) => s.traveling);
  const storedPace = useSessionStore((s) => s.travelPace);

  const [destination, setDestination] = useState<RegionNode | null>(null);
  const [pace, setPace] = useState<TravelPace>(storedPace);

  // Keep the picker in sync with the party's last-used pace between trips.
  useEffect(() => setPace(storedPace), [storedPace]);

  const nodes = map?.regions ?? [];
  const from = currentRegion
    ? nodes.find((n) => norm(n.name) === norm(currentRegion)) ?? null
    : null;

  function handleConfirm() {
    if (!destination) return;
    onTravel(destination.name, pace);
    setDestination(null);
  }

  return (
    <div className="relative flex h-full min-h-0 flex-col border-r border-border bg-bg-elevated">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <h2 className="font-display text-sm font-bold tracking-wide text-gold">
          Campaign Map
        </h2>
        {traveling && (
          <span className="animate-pulse text-xs italic text-text-muted">
            Traveling…
          </span>
        )}
      </div>

      <div className="relative min-h-0 flex-1">
        {nodes.length === 0 ? (
          <div className="flex h-full items-center justify-center p-6 text-center text-sm text-text-muted">
            This adventure has no map to travel.
          </div>
        ) : (
          <TravelMap
            nodes={nodes}
            currentRegion={currentRegion}
            traveling={traveling}
            onPickDestination={setDestination}
          />
        )}
      </div>

      {/* Legend (shape + text, not colour alone) */}
      {nodes.length > 0 && (
        <div className="flex items-center gap-4 border-t border-border px-4 py-1.5 text-[10px] text-text-muted">
          <span className="inline-flex items-center gap-1">
            <span className="inline-block h-2 w-2 rounded-full bg-accent" /> You are here
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="inline-block h-2 w-2 rounded-full border border-gold" /> Reachable
          </span>
        </div>
      )}

      <TravelConfirmModal
        open={!!destination}
        destination={destination}
        from={from}
        pace={pace}
        onPaceChange={setPace}
        onConfirm={handleConfirm}
        onClose={() => setDestination(null)}
        connected={connected}
      />
    </div>
  );
}
