import type { TerrainType } from "@/types";
import { TERRAIN_LABEL } from "./constants";

/** A small inline terrain key swatch + label (matches the SVG treatment). */
function LegendSwatch({ type }: { type: TerrainType }) {
  return (
    <span className="inline-flex items-center gap-1">
      <svg width={14} height={14} className="rounded-sm" aria-hidden="true">
        {type === "WALL" && (
          <>
            <rect width={14} height={14} fill="var(--color-bg-elevated)" />
            <path
              d="M0 14 L0 0 L14 0"
              fill="none"
              stroke="var(--color-border)"
              strokeWidth={2}
            />
          </>
        )}
        {type === "DIFFICULT" && (
          <>
            <rect width={14} height={14} fill="#2dd4bf" fillOpacity={0.22} />
            <path
              d="M-2 4 L4 -2 M-2 12 L12 -2 M2 16 L16 2"
              stroke="#5eead4"
              strokeWidth={2}
              strokeOpacity={0.8}
            />
            <rect
              x={0.5}
              y={0.5}
              width={13}
              height={13}
              fill="none"
              stroke="#2dd4bf"
              strokeOpacity={0.6}
            />
          </>
        )}
        {type === "HAZARD" && (
          <>
            <rect width={14} height={14} fill="var(--color-danger)" fillOpacity={0.2} />
            <rect
              x={0.5}
              y={0.5}
              width={13}
              height={13}
              fill="none"
              stroke="var(--color-danger)"
              strokeOpacity={0.7}
            />
            <path d="M7 2 L12 12 L2 12 Z" fill="var(--color-danger)" />
          </>
        )}
      </svg>
      {TERRAIN_LABEL[type]}
    </span>
  );
}

/** Terrain legend strip shown beneath the board. */
export default function TerrainLegend() {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-text-muted">
      <LegendSwatch type="WALL" />
      <LegendSwatch type="DIFFICULT" />
      <LegendSwatch type="HAZARD" />
      <span className="inline-flex items-center gap-1">
        <span className="inline-block h-2.5 w-2.5 rounded-full border-2 border-[var(--color-gold)]" />
        Feature
      </span>
    </div>
  );
}
