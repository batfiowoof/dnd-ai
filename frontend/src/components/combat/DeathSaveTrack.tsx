"use client";

import { useEffect, useState } from "react";
import type { PlayerRuntimeState } from "@/types";
import { cn } from "@/components/ui";

export type DeathStatus = "DYING" | "STABLE" | "DEAD";

/** The runtime fields needed to read a creature's death-save state. */
type DeathInput = Pick<
  PlayerRuntimeState,
  "currentHp" | "dead" | "stable"
> | null | undefined;

/**
 * Derive a creature's death-save status from its runtime state:
 *  - DEAD: failed three saves.
 *  - DYING: at 0 HP, not yet stable — still rolling saves each turn.
 *  - STABLE: stabilized at 0 HP (unconscious, no longer rolling).
 *  - null: conscious / not relevant.
 */
export function deriveDeathStatus(state: DeathInput): DeathStatus | null {
  if (!state) return null;
  if (state.dead) return "DEAD";
  if (state.currentHp > 0) return null;
  return state.stable ? "STABLE" : "DYING";
}

/** Honour the OS reduced-motion setting AND the in-app preference flag. */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    const read = () =>
      setReduced(
        !!mq?.matches ||
          document.documentElement.dataset.reduceMotion === "true"
      );
    read();
    mq?.addEventListener?.("change", read);
    return () => mq?.removeEventListener?.("change", read);
  }, []);
  return reduced;
}

type PipSize = "xs" | "sm";

const PIP: Record<PipSize, string> = {
  xs: "h-1.5 w-1.5",
  sm: "h-2 w-2",
};

interface DeathSaveTrackProps {
  successes: number;
  failures: number;
  /** `xs` for a token overlay, `sm` (default) for cards/panels. */
  size?: PipSize;
  className?: string;
}

function Pips({
  count,
  pip,
  filled,
  empty,
  reduced,
  keyPrefix,
}: {
  count: number;
  pip: string;
  filled: string;
  empty: string;
  reduced: boolean;
  keyPrefix: string;
}) {
  return (
    <span className="inline-flex gap-0.5" aria-hidden="true">
      {[0, 1, 2].map((i) => (
        <span
          key={`${keyPrefix}${i}`}
          className={cn(
            "rounded-full border",
            pip,
            i < count ? filled : empty,
            !reduced && "transition-colors"
          )}
        />
      ))}
    </span>
  );
}

/**
 * Three success pips (green) + three failure pips (red), filled left-to-right.
 * Tiny enough to sit inside a token overlay or an ally card. Meaning is conveyed
 * by grouping + position (and an aria-label), never by colour alone.
 */
export default function DeathSaveTrack({
  successes,
  failures,
  size = "sm",
  className,
}: DeathSaveTrackProps) {
  const reduced = usePrefersReducedMotion();
  const s = Math.max(0, Math.min(3, successes));
  const f = Math.max(0, Math.min(3, failures));
  const pip = PIP[size];

  return (
    <div
      className={cn("inline-flex items-center gap-1", className)}
      role="img"
      aria-label={`Death saves: ${s} ${
        s === 1 ? "success" : "successes"
      }, ${f} ${f === 1 ? "failure" : "failures"}`}
    >
      <Pips
        count={s}
        pip={pip}
        filled="border-success bg-success"
        empty="border-success/40 bg-transparent"
        reduced={reduced}
        keyPrefix="s"
      />
      <span
        className="select-none text-[8px] leading-none text-text-muted"
        aria-hidden="true"
      >
        /
      </span>
      <Pips
        count={f}
        pip={pip}
        filled="border-danger bg-danger"
        empty="border-danger/40 bg-transparent"
        reduced={reduced}
        keyPrefix="f"
      />
    </div>
  );
}

const BADGE: Record<DeathStatus, { label: string; cls: string }> = {
  DYING: { label: "Dying", cls: "border-danger/50 bg-danger/15 text-danger" },
  STABLE: { label: "Stable", cls: "border-gold/50 bg-gold-muted text-gold" },
  DEAD: { label: "Dead", cls: "border-border bg-surface-light text-text-muted" },
};

interface StatusBadgeProps {
  status: DeathStatus;
  className?: string;
}

/**
 * Compact death-status badge: text label + colour (never colour alone). DYING
 * pulses to draw the eye, stilled under reduced motion.
 */
export function StatusBadge({ status, className }: StatusBadgeProps) {
  const reduced = usePrefersReducedMotion();
  const { label, cls } = BADGE[status];
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center rounded border px-1.5 py-0.5 text-[9px] font-bold uppercase leading-none tracking-wider",
        cls,
        status === "DYING" && !reduced && "animate-pulse",
        className
      )}
    >
      {label}
    </span>
  );
}
