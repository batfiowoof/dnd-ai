"use client";

import type { RegionNode, TravelPace } from "@/types";
import { Button, Modal, SegmentedControl } from "@/components/ui";
import { PACE_BLURB, PACE_OPTIONS, estimateTravel, estimateLocalTravel } from "@/lib/travel";

interface TravelConfirmModalProps {
  open: boolean;
  /** The chosen destination, or null when closed. */
  destination: RegionNode | null;
  /** Where the party is setting out from (null on first placement). */
  from: RegionNode | null;
  pace: TravelPace;
  onPaceChange: (pace: TravelPace) => void;
  onConfirm: () => void;
  onClose: () => void;
  /** WebSocket connectivity — the confirm is disabled while offline. */
  connected: boolean;
  /** True when this is a local hop between subregions (minutes, no encounter, no pace tradeoff). */
  local?: boolean;
}

/** Confirms a travel leg: shows the destination, route, computed time, and a pace picker. */
export default function TravelConfirmModal({
  open,
  destination,
  from,
  pace,
  onPaceChange,
  onConfirm,
  onClose,
  connected,
  local = false,
}: TravelConfirmModalProps) {
  if (!destination) return null;

  const estimate = local
    ? estimateLocalTravel(from, destination)
    : estimateTravel(from, destination, pace);

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="sm"
      title={local ? `Head to ${destination.name}` : `Travel to ${destination.name}`}
    >
      <div className="space-y-4">
        {destination.type && (
          <span className="inline-block rounded bg-gold-muted px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-gold">
            {destination.type}
          </span>
        )}

        {destination.description && (
          <p className="text-sm leading-relaxed text-text-muted">
            {destination.description}
          </p>
        )}

        {/* Route + estimate */}
        <div className="flex items-center justify-between rounded-lg border border-border bg-bg-elevated px-3 py-2 text-sm">
          <span className="text-text-muted">
            {from ? (
              <>
                <span className="text-text">{from.name}</span>
                <span className="mx-1.5 text-gold">→</span>
                <span className="text-text">{destination.name}</span>
              </>
            ) : (
              <span className="text-text">{destination.name}</span>
            )}
          </span>
          <span className="tabular text-gold">{estimate.durationText}</span>
        </div>

        {/* Pace (overland only — a local hop has no march pace or ambush risk) */}
        {local ? (
          <p className="text-xs italic text-text-muted">
            A short move within the area — no wilderness encounter.
          </p>
        ) : (
          <div>
            <p className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-text-muted">
              Pace
            </p>
            <SegmentedControl<TravelPace>
              value={pace}
              onChange={onPaceChange}
              options={PACE_OPTIONS}
            />
            <p className="mt-1.5 text-xs italic text-text-muted">{PACE_BLURB[pace]}</p>
          </div>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={!connected}>
            Set out
          </Button>
        </div>
      </div>
    </Modal>
  );
}
