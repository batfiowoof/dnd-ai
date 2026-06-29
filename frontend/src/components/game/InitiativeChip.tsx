"use client";

import { cn } from "@/components/ui";

/** Small d20 initiative badge shown on a combatant's avatar during combat. */
export default function InitiativeChip({
  value,
  active,
}: {
  value: number;
  active?: boolean;
}) {
  return (
    <span
      title={`Initiative ${value}`}
      className={cn(
        "tabular absolute -right-1.5 -top-1.5 flex h-5 min-w-[1.25rem] items-center justify-center rounded-full border px-1 text-[10px] font-bold shadow",
        active
          ? "border-gold bg-gold text-bg"
          : "border-border-accent bg-surface text-gold"
      )}
    >
      {value}
    </span>
  );
}
