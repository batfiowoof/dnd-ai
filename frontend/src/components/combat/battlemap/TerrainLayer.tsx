import type { GridState } from "@/types";
import { CELL } from "./constants";

/** Terrain cells — WALL / DIFFICULT / HAZARD (pattern + icon, never colour alone) (layer 4). */
export default function TerrainLayer({ grid }: { grid: GridState }) {
  return (
    <g>
      {grid.terrain.map((t) => {
        const tx = t.x * CELL;
        const ty = t.y * CELL;
        if (t.type === "WALL") {
          return (
            <g key={`t${t.x}-${t.y}`}>
              <rect
                x={tx}
                y={ty}
                width={CELL}
                height={CELL}
                fill="var(--color-bg-elevated)"
              />
              {/* top/left edge highlight for a chiselled-stone read */}
              <path
                d={`M${tx} ${ty + CELL} L${tx} ${ty} L${tx + CELL} ${ty}`}
                fill="none"
                stroke="var(--color-border)"
                strokeWidth={2}
              />
              {/* full outline so the wall reads even over a map image */}
              <rect
                x={tx + 0.5}
                y={ty + 0.5}
                width={CELL - 1}
                height={CELL - 1}
                fill="none"
                stroke="#6b7280"
                strokeOpacity={0.7}
                strokeWidth={1.5}
              />
            </g>
          );
        }
        if (t.type === "DIFFICULT") {
          return (
            <g key={`t${t.x}-${t.y}`}>
              <rect x={tx} y={ty} width={CELL} height={CELL} fill="url(#bm-difficult)" />
              <rect
                x={tx + 0.5}
                y={ty + 0.5}
                width={CELL - 1}
                height={CELL - 1}
                fill="none"
                stroke="#2dd4bf"
                strokeOpacity={0.6}
                strokeWidth={1.5}
              />
            </g>
          );
        }
        // HAZARD — strong red tint + outline + spike glyph.
        return (
          <g key={`t${t.x}-${t.y}`}>
            <rect
              x={tx}
              y={ty}
              width={CELL}
              height={CELL}
              fill="var(--color-danger)"
              fillOpacity={0.2}
            />
            <rect
              x={tx + 0.5}
              y={ty + 0.5}
              width={CELL - 1}
              height={CELL - 1}
              fill="none"
              stroke="var(--color-danger)"
              strokeOpacity={0.7}
              strokeWidth={1.5}
            />
            <path
              d={`M${tx + CELL / 2} ${ty + 10} L${tx + CELL - 12} ${
                ty + CELL - 12
              } L${tx + 12} ${ty + CELL - 12} Z`}
              fill="var(--color-danger)"
              opacity={0.9}
            />
          </g>
        );
      })}
    </g>
  );
}
