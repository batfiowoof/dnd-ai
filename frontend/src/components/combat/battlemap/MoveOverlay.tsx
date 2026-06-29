"use client";

import { useState } from "react";
import type { Token } from "@/types";
import { cellKey, colLetter, gridDistanceFeet } from "@/lib/grid";
import { CELL } from "./constants";

/**
 * Reachable-move highlight (active player's turn only; hidden while targeting) (layer 6).
 * Owns the hovered-cell state and calls `onMove` when a reachable empty cell is activated.
 * Renders nothing unless `show` (interactive && !targeting).
 */
export default function MoveOverlay({
  show,
  reachable,
  occupied,
  myToken,
  reduced,
  onMove,
}: {
  show: boolean;
  reachable: Set<string>;
  occupied: Set<string>;
  myToken: Token | null;
  reduced: boolean;
  onMove: (x: number, y: number) => void;
}) {
  const [hovered, setHovered] = useState<string | null>(null);
  if (!show) return null;
  return (
    <g>
      {[...reachable].map((k) => {
        if (occupied.has(k)) return null;
        const [cx, cy] = k.split(",").map(Number);
        const isHover = hovered === k;
        return (
          <g
            key={`r${k}`}
            role="button"
            tabIndex={0}
            aria-label={`Move to ${colLetter(cx)}${cy + 1} (${gridDistanceFeet(
              myToken!.x,
              myToken!.y,
              cx,
              cy
            )} ft)`}
            className="cursor-pointer focus:outline-none"
            onClick={() => onMove(cx, cy)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onMove(cx, cy);
              }
            }}
            onMouseEnter={() => setHovered(k)}
            onMouseLeave={() => setHovered((h) => (h === k ? null : h))}
            onFocus={() => setHovered(k)}
            onBlur={() => setHovered((h) => (h === k ? null : h))}
          >
            <rect
              x={cx * CELL + 2}
              y={cy * CELL + 2}
              width={CELL - 4}
              height={CELL - 4}
              rx={4}
              fill="var(--color-accent-glow)"
              stroke="var(--color-accent)"
              strokeOpacity={isHover ? 0.9 : 0.35}
              strokeWidth={isHover ? 2 : 1}
              className={reduced ? undefined : "transition-all"}
            />
            {isHover && (
              <text
                x={cx * CELL + CELL / 2}
                y={cy * CELL + CELL / 2 + 3}
                textAnchor="middle"
                className="tabular pointer-events-none"
                fontSize={10}
                fill="var(--color-accent-light)"
              >
                {gridDistanceFeet(myToken!.x, myToken!.y, cx, cy)}ft
              </text>
            )}
          </g>
        );
      })}
    </g>
  );
}
