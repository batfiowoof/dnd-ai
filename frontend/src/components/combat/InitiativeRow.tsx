import type { Combatant } from "@/types";
import { cn } from "@/components/ui";

interface InitiativeRowProps {
  order: Combatant[];
  activeIndex: number;
}

/** The header initiative order: a pill per combatant, the active one highlighted. */
export default function InitiativeRow({ order, activeIndex }: InitiativeRowProps) {
  return (
    <div className="mb-3 flex flex-wrap gap-1">
      {order.map((c, i) => (
        <span
          key={`${c.refId}-${i}`}
          className={cn(
            "inline-flex items-center gap-1 rounded px-2 py-0.5 text-[10px] tabular transition",
            i === activeIndex
              ? "bg-accent text-white"
              : c.kind === "ENEMY"
                ? "bg-surface-light text-accent-light"
                : "bg-surface-light text-text-muted"
          )}
          title={`Initiative ${c.initiative}`}
        >
          <span
            className={cn(
              "inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[9px] font-bold",
              i === activeIndex ? "bg-white/25 text-white" : "bg-bg/60 text-gold"
            )}
          >
            {c.initiative}
          </span>
          {c.name}
        </span>
      ))}
    </div>
  );
}
