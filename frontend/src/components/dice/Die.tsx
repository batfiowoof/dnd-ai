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
 * A single themed die. Hexagonal silhouette (generic d20 look) with the face
 * value centered. Tumbles while `rolling`, then pops to its resting value.
 * Crit (max) glows gold, fumble (1) dims to danger red.
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
      {/* Die body — hexagonal silhouette */}
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
          clipPath:
            "polygon(50% 0%, 95% 25%, 95% 75%, 50% 100%, 5% 75%, 5% 25%)",
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
