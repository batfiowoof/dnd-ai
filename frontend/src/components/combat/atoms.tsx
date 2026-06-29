import { cn } from "@/components/ui";
import { conditionMeta, conditionChipClasses } from "@/lib/conditions";

/** One action-economy pip: filled when available, hollow + struck when spent. */
export function EconomyPip({ label, spent }: { label: string; spent: boolean }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded px-1 py-0.5 font-semibold",
        spent ? "text-text-muted/60 line-through" : "text-gold"
      )}
      title={spent ? `${label} used` : `${label} available`}
    >
      <span
        className={cn(
          "inline-block h-1.5 w-1.5 rounded-full",
          spent ? "bg-text-muted/40" : "bg-gold"
        )}
      />
      {label}
    </span>
  );
}

/** Inline row of condition chips (code + tooltip) for an enemy/ally card. */
export function ConditionChips({ conditions }: { conditions?: string[] | null }) {
  if (!conditions || conditions.length === 0) return null;
  return (
    <div className="mt-1 flex flex-wrap gap-1">
      {conditions.map((name) => {
        const meta = conditionMeta(name);
        return (
          <span
            key={name}
            title={`${meta.label} — ${meta.hint}`}
            className={cn(
              "rounded border px-1 py-px text-[8px] font-semibold uppercase tracking-wide",
              conditionChipClasses(meta.tone)
            )}
          >
            {meta.label}
          </span>
        );
      })}
    </div>
  );
}
