import type { GridState } from "@/types";

/** Background image + legibility scrim, board fill, and edge vignette (layers 1, 2, 2b). */
export default function BackgroundLayer({
  grid,
  W,
  H,
}: {
  grid: GridState;
  W: number;
  H: number;
}) {
  return (
    <>
      {/* 1 — Background image + legibility scrim */}
      {grid.backgroundImageUrl && (
        <g>
          <image
            href={grid.backgroundImageUrl}
            x={0}
            y={0}
            width={W}
            height={H}
            preserveAspectRatio="xMidYMid slice"
          />
          <rect x={0} y={0} width={W} height={H} fill="#0b0a0a" opacity={0.45} />
        </g>
      )}

      {/* 2 — Board fill (skipped when an image already covers it) */}
      {!grid.backgroundImageUrl && (
        <rect x={0} y={0} width={W} height={H} fill="var(--color-surface)" />
      )}

      {/* 2b — Vignette (depth on the board edges) */}
      <rect
        x={0}
        y={0}
        width={W}
        height={H}
        fill="url(#bm-vignette)"
        pointerEvents="none"
      />
    </>
  );
}
