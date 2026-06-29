import type { GridState } from "@/types";
import { CELL } from "./constants";

/** Grid lines (layer 3). */
export default function GridLayer({
  grid,
  W,
  H,
}: {
  grid: GridState;
  W: number;
  H: number;
}) {
  return (
    <g
      stroke="var(--color-border)"
      strokeWidth={1}
      opacity={0.5}
      shapeRendering="crispEdges"
    >
      {Array.from({ length: grid.width + 1 }).map((_, i) => (
        <line key={`v${i}`} x1={i * CELL} y1={0} x2={i * CELL} y2={H} />
      ))}
      {Array.from({ length: grid.height + 1 }).map((_, i) => (
        <line key={`h${i}`} x1={0} y1={i * CELL} x2={W} y2={i * CELL} />
      ))}
    </g>
  );
}
