"use client";

import { cn } from "@/components/ui";

interface DieProps {
  /** Number currently shown on the face. */
  value: number;
  sides: number;
  rolling: boolean;
  crit?: boolean;
  fumble?: boolean;
  size?: number; // px
}

/**
 * Per-die silhouettes (clip-path polygons within the square box), so a d4 rolls
 * as a triangle, a d6 as a square, etc. Unknown sizes (e.g. d100) fall back to
 * the d20 hexagon.
 */
const HEXAGON = "polygon(50% 0%, 95% 25%, 95% 75%, 50% 100%, 5% 75%, 5% 25%)";
const DIE_CLIP: Record<number, string> = {
  4: "polygon(50% 5%, 95% 92%, 5% 92%)", // upward triangle
  6: "polygon(12% 12%, 88% 12%, 88% 88%, 12% 88%)", // square (cube face)
  8: "polygon(50% 2%, 95% 50%, 50% 98%, 5% 50%)", // diamond / octahedron
  10: "polygon(50% 0%, 90% 38%, 50% 100%, 10% 38%)", // kite
  12: "polygon(50% 2%, 98% 39%, 80% 96%, 20% 96%, 2% 39%)", // pentagon
  20: HEXAGON,
};

/**
 * A single themed die whose silhouette matches its `sides` (triangle d4, square
 * d6, diamond d8, kite d10, pentagon d12, hexagon d20), with the face value
 * centered. Tumbles while `rolling`, then pops to its resting value. Crit (max)
 * glows gold, fumble (1) dims to danger red.
 */
export default function Die({
  value,
  sides,
  rolling,
  crit = false,
  fumble = false,
  size = 64,
}: DieProps) {
  return (
    <div
      className={cn(
        "relative grid shrink-0 place-items-center",
        rolling ? "animate-dice-tumble" : "animate-dice-settle"
      )}
      style={{ width: size, height: size }}
    >
      {/* Die body — silhouette per `sides` (see DIE_CLIP) */}
      <div
        className={cn(
          "absolute inset-0 border",
          crit && !rolling
            ? "border-gold shadow-[0_0_18px_var(--color-gold-muted)]"
            : fumble && !rolling
              ? "border-danger"
              : "border-border-accent"
        )}
        style={{
          clipPath: DIE_CLIP[sides] ?? HEXAGON,
          background:
            crit && !rolling
              ? "linear-gradient(160deg, var(--color-surface-light), var(--color-gold-muted))"
              : "linear-gradient(160deg, var(--color-surface-light), var(--color-accent-glow))",
        }}
        aria-hidden="true"
      />
      <span
        className={cn(
          "relative font-mono font-bold tabular-nums",
          crit && !rolling
            ? "text-gold"
            : fumble && !rolling
              ? "text-danger"
              : "text-text"
        )}
        style={{ fontSize: size * 0.4 }}
      >
        {value}
      </span>
      {sides > 0 && (
        <span
          className="absolute bottom-1 text-[8px] font-semibold uppercase tracking-wider text-text-muted"
          aria-hidden="true"
        >
          d{sides}
        </span>
      )}
    </div>
  );
}
