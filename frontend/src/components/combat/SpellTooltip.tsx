"use client";

import type { ReactNode } from "react";
import type { SpellSummary } from "@/types";
import { Tooltip } from "@/components/ui";
import { spellMechanics } from "@/lib/combat";

/**
 * A themed hover/focus tooltip for a spell — name, level/school, casting time, range,
 * concentration, the parsed mechanics line, and the flavour summary. Built on the shared
 * `Tooltip` primitive (portal + collision-flip), so it escapes the scrollable cast menu and
 * stays on-screen. Wrap any spell trigger (a menu row, a chip) with it.
 */
export default function SpellTooltip({
  spell,
  placement = "left",
  className,
  children,
}: {
  spell: SpellSummary;
  placement?: "top" | "right" | "bottom" | "left";
  className?: string;
  children: ReactNode;
}) {
  const bonus = spell.castingTime === "Bonus Action";
  const levelLabel = spell.level === 0 ? "Cantrip" : `Level ${spell.level}`;

  const content = (
    <span className="block w-56 text-left">
      <span className="flex items-baseline justify-between gap-2">
        <span className="font-display text-sm font-bold text-accent">
          {spell.name}
        </span>
        <span className="flex-shrink-0 text-[9px] uppercase tracking-wider text-text-muted">
          {levelLabel}
        </span>
      </span>

      {spell.school && (
        <span className="block text-[10px] italic text-text-muted">
          {spell.school}
        </span>
      )}

      <span className="mt-1.5 flex flex-wrap gap-1">
        <Tag tone={bonus ? "gold" : "muted"}>
          {bonus ? "⚡ Bonus Action" : spell.castingTime}
        </Tag>
        {spell.range && <Tag tone="muted">{spell.range}</Tag>}
        {spell.concentration && <Tag tone="accent">Concentration</Tag>}
      </span>

      <span className="mt-1.5 block text-[11px] font-medium text-gold/90">
        {spellMechanics(spell)}
      </span>

      {spell.summary && (
        <span className="mt-1 block text-[11px] leading-snug text-text-muted">
          {spell.summary}
        </span>
      )}
    </span>
  );

  return (
    <Tooltip content={content} placement={placement} className={className}>
      {children}
    </Tooltip>
  );
}

function Tag({
  tone,
  children,
}: {
  tone: "gold" | "accent" | "muted";
  children: ReactNode;
}) {
  const tones = {
    gold: "border-gold/40 text-gold",
    accent: "border-accent/50 text-accent",
    muted: "border-border text-text-muted",
  } as const;
  return (
    <span
      className={`rounded border px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide ${tones[tone]}`}
    >
      {children}
    </span>
  );
}
