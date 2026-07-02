"use client";

import { useEffect, useState } from "react";
import type { RegionNode, TravelMapDto, TravelPace } from "@/types";
import { useSessionStore } from "@/store/sessionStore";
import { Button } from "@/components/ui";
import TravelMap from "./TravelMap";
import TravelConfirmModal from "./TravelConfirmModal";

interface TravelPanelProps {
  map: TravelMapDto | undefined;
  connected: boolean;
  /** Overland travel to a route-connected region (parent flips the store's `traveling` flag). */
  onTravel: (destinationRegion: string, pace: TravelPace) => void;
  /** Local hop to a subregion within the current region. */
  onTravelLocal: (destinationSubregion: string, pace: TravelPace) => void;
}

const norm = (s: string) => s.trim().toLowerCase();

/**
 * The out-of-combat map pane: frames the campaign {@link TravelMap} and tracks the pick→confirm flow
 * at two levels. The overland view moves the party between regions; "entering" the region they're in
 * drills down to a local mini-map of its subregions, where moves are short local hops. Reads the
 * party's live position and travel state from the store.
 */
export default function TravelPanel({ map, connected, onTravel, onTravelLocal }: TravelPanelProps) {
  const currentRegion = useSessionStore((s) => s.currentRegion);
  const currentSubregion = useSessionStore((s) => s.currentSubregion);
  const traveling = useSessionStore((s) => s.traveling);
  const storedPace = useSessionStore((s) => s.travelPace);

  /** The region we've drilled into (its name), or null for the overland view. */
  const [localRegionName, setLocalRegionName] = useState<string | null>(null);
  const [destination, setDestination] = useState<RegionNode | null>(null);
  const [pace, setPace] = useState<TravelPace>(storedPace);

  // Keep the picker in sync with the party's last-used pace between trips.
  useEffect(() => setPace(storedPace), [storedPace]);
  // Leaving a region for another (overland) drops us back to the overland view.
  useEffect(() => setLocalRegionName(null), [currentRegion]);

  const regions = map?.regions ?? [];
  const fromRegion = currentRegion
    ? regions.find((n) => norm(n.name) === norm(currentRegion)) ?? null
    : null;
  const localRegion = localRegionName
    ? regions.find((n) => norm(n.name) === norm(localRegionName)) ?? null
    : null;

  const inLocalView = !!localRegion;
  const nodes = inLocalView ? localRegion.subregions ?? [] : regions;
  // In the local view the "current" pin is the party's subregion; overland it's the region.
  const currentNodeName = inLocalView ? currentSubregion : currentRegion;
  const from = inLocalView
    ? currentSubregion
      ? nodes.find((n) => norm(n.name) === norm(currentSubregion)) ?? null
      : null
    : fromRegion;

  // The party can drill into the region they're actually standing in, if it has subregions.
  const canEnter =
    !inLocalView && !!fromRegion && (fromRegion.subregions?.length ?? 0) > 0;

  function handleConfirm() {
    if (!destination) return;
    if (inLocalView) onTravelLocal(destination.name, pace);
    else onTravel(destination.name, pace);
    setDestination(null);
  }

  return (
    <div className="relative flex h-full min-h-0 flex-col border-r border-border bg-bg-elevated">
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-2">
        <div className="flex min-w-0 items-center gap-2">
          {inLocalView && (
            <button
              type="button"
              onClick={() => setLocalRegionName(null)}
              className="flex cursor-pointer items-center gap-1 rounded text-xs text-text-muted transition hover:text-gold"
              aria-label="Back to the overland map"
            >
              <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                <path d="M15 18l-6-6 6-6" />
              </svg>
              Overland
            </button>
          )}
          <h2 className="truncate font-display text-sm font-bold tracking-wide text-gold">
            {inLocalView ? localRegion.name : "Campaign Map"}
          </h2>
        </div>
        {traveling && (
          <span className="animate-pulse text-xs italic text-text-muted">Traveling…</span>
        )}
      </div>

      <div className="relative min-h-0 flex-1">
        {nodes.length === 0 ? (
          <div className="flex h-full items-center justify-center p-6 text-center text-sm text-text-muted">
            {inLocalView
              ? "This area has no places to explore within it."
              : "This adventure has no map to travel."}
          </div>
        ) : (
          <TravelMap
            nodes={nodes}
            currentRegion={currentNodeName}
            traveling={traveling}
            onPickDestination={setDestination}
          />
        )}

        {/* Drill-in affordance: available only for the region the party is standing in. */}
        {canEnter && (
          <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center">
            <Button
              variant="outline"
              size="sm"
              className="pointer-events-auto shadow-lg"
              onClick={() => setLocalRegionName(fromRegion!.name)}
            >
              Explore {fromRegion!.name}
            </Button>
          </div>
        )}
      </div>

      {/* Legend (shape + text, not colour alone) */}
      {nodes.length > 0 && (
        <div className="flex items-center gap-4 border-t border-border px-4 py-1.5 text-[10px] text-text-muted">
          <span className="inline-flex items-center gap-1">
            <span className="inline-block h-2 w-2 rounded-full bg-accent" /> You are here
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="inline-block h-2 w-2 rounded-full border border-gold" />{" "}
            {inLocalView ? "Nearby" : "Reachable"}
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
        local={inLocalView}
      />
    </div>
  );
}
