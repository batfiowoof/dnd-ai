"use client";

import type { GridState } from "@/types";
import { cellKey, colLetter } from "@/lib/grid";
import { CELL } from "./constants";
import type { PlacingSpell } from "./types";

/**
 * AoE template preview — the gold cells shown beneath the tokens while aiming (layer 7).
 * Driven by `previewCells` (computed from the shared `placeHover` origin in the parent).
 *
 * NOTE: the preview (here) and the transparent capture grid ({@link AoeCaptureLayer}) are kept
 * in this same module so they stay together, but they render at different SVG z-positions — the
 * preview below the tokens, the capture grid on top of everything — so the shared `placeHover`
 * is owned by the parent rather than a single wrapping component.
 */
export function AoePreviewLayer({
  show,
  previewCells,
  placeHover,
}: {
  show: boolean;
  previewCells: Set<string>;
  placeHover: string | null;
}) {
  if (!show) return null;
  return (
    <g aria-hidden="true" pointerEvents="none">
      {[...previewCells].map((k) => {
        const [cx, cy] = k.split(",").map(Number);
        const isOrigin = k === placeHover;
        return (
          <rect
            key={`aoe${k}`}
            x={cx * CELL + 1}
            y={cy * CELL + 1}
            width={CELL - 2}
            height={CELL - 2}
            rx={3}
            fill="var(--color-gold)"
            fillOpacity={isOrigin ? 0.42 : 0.26}
            stroke="var(--color-gold)"
            strokeOpacity={0.85}
            strokeWidth={isOrigin ? 2 : 1}
          />
        );
      })}
    </g>
  );
}

/**
 * Transparent AoE placement capture grid drawn on top of the board (layer 10): every cell
 * hovers to set the shared `placeHover` origin and clicks to commit the cast at that cell.
 */
export function AoeCaptureLayer({
  show,
  grid,
  placingSpell,
  connected,
  onCastAoe,
  setPlaceHover,
}: {
  show: boolean;
  grid: GridState;
  placingSpell: PlacingSpell;
  connected: boolean;
  onCastAoe: (spellName: string, spellLevel: number, x: number, y: number) => void;
  setPlaceHover: (updater: string | null | ((h: string | null) => string | null)) => void;
}) {
  if (!show) return null;
  return (
    <g>
      {Array.from({ length: grid.height }).map((_, cy) =>
        Array.from({ length: grid.width }).map((_, cx) => {
          const k = cellKey(cx, cy);
          return (
            <rect
              key={`aim${k}`}
              x={cx * CELL}
              y={cy * CELL}
              width={CELL}
              height={CELL}
              fill="transparent"
              role="button"
              tabIndex={0}
              aria-label={`Aim ${placingSpell.name} at ${colLetter(
                cx
              )}${cy + 1}`}
              className="cursor-crosshair focus:outline-none"
              onMouseEnter={() => setPlaceHover(k)}
              onMouseLeave={() =>
                setPlaceHover((h) => (h === k ? null : h))
              }
              onFocus={() => setPlaceHover(k)}
              onClick={() =>
                connected &&
                onCastAoe(
                  placingSpell.name,
                  placingSpell.level,
                  cx,
                  cy
                )
              }
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  if (connected) {
                    onCastAoe(
                      placingSpell.name,
                      placingSpell.level,
                      cx,
                      cy
                    );
                  }
                }
              }}
            />
          );
        })
      )}
    </g>
  );
}
