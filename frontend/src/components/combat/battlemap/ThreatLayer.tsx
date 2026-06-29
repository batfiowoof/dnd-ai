import { CELL } from "./constants";

/**
 * Opportunity-attack threat zones (enemy melee reach) shown on the player's turn (layer 5.5).
 * Renders nothing unless `show` (isMyTurn && !placing).
 */
export default function ThreatLayer({
  show,
  threatened,
}: {
  show: boolean;
  threatened: Set<string>;
}) {
  if (!show) return null;
  return (
    <g aria-hidden="true" pointerEvents="none">
      {[...threatened].map((k) => {
        const [tx, ty] = k.split(",").map(Number);
        return (
          <rect
            key={`threat${k}`}
            x={tx * CELL + 1}
            y={ty * CELL + 1}
            width={CELL - 2}
            height={CELL - 2}
            rx={3}
            fill="var(--color-danger)"
            fillOpacity={0.06}
            stroke="var(--color-danger)"
            strokeOpacity={0.25}
            strokeDasharray="2 4"
            strokeWidth={1}
          />
        );
      })}
    </g>
  );
}
