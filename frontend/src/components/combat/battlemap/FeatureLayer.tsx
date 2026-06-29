import type { GridState } from "@/types";
import { CELL } from "./constants";

/** Feature markers — gold diamond + label (layer 5). */
export default function FeatureLayer({ grid }: { grid: GridState }) {
  return (
    <g>
      {grid.features.map((f) => {
        const fx = f.x * CELL + CELL / 2;
        const fy = f.y * CELL + CELL / 2;
        return (
          <g key={`f${f.x}-${f.y}`}>
            <rect
              x={fx - 5}
              y={fy - 5}
              width={10}
              height={10}
              transform={`rotate(45 ${fx} ${fy})`}
              fill="var(--color-gold)"
              opacity={0.85}
            />
            <text
              x={fx}
              y={fy + CELL / 2 - 4}
              textAnchor="middle"
              style={{ fontFamily: "var(--font-display)" }}
              fontSize={9}
              fill="var(--color-gold)"
            >
              {f.label}
            </text>
          </g>
        );
      })}
    </g>
  );
}
