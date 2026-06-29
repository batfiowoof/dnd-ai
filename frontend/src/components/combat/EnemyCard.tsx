import type { EnemyDto } from "@/types";
import { cn } from "@/components/ui";
import type { BandMeta } from "@/lib/health";
import { ConditionChips } from "@/components/combat/atoms";

interface EnemyCardProps {
  enemy: EnemyDto;
  band: BandMeta;
  /** This enemy's initiative, or undefined if it isn't in the order. */
  initiative: number | undefined;
  /** Can be clicked to attack right now (your turn, in range, action free). */
  attackable: boolean;
  /** Can be picked as a spell target while casting offence. */
  selectable: boolean;
  /** Already picked as a target. */
  chosen: boolean;
  onAttack: () => void;
  onSelect: () => void;
}

/** One enemy in the combat tracker grid: HP band bar, AC, initiative, conditions. */
export default function EnemyCard({
  enemy: e,
  band,
  initiative,
  attackable,
  selectable,
  chosen,
  onAttack,
  onSelect,
}: EnemyCardProps) {
  return (
    <button
      type="button"
      disabled={!attackable && !selectable}
      onClick={() => (selectable ? onSelect() : attackable && onAttack())}
      className={cn(
        "rounded-lg border p-2 text-left transition",
        !e.alive
          ? "border-border bg-surface/40 opacity-50"
          : chosen
            ? "border-accent bg-accent-glow shadow-[0_0_16px_var(--color-accent-glow)]"
            : attackable || selectable
              ? "cursor-pointer border-accent/60 bg-surface hover:border-accent hover:shadow-[0_0_16px_var(--color-accent-glow)]"
              : "border-border bg-surface"
      )}
      title={
        selectable
          ? `Target ${e.name}`
          : attackable
            ? `Attack ${e.name}`
            : e.name
      }
    >
      <div className="flex items-center justify-between">
        <span
          className={cn(
            "truncate text-xs font-semibold",
            e.alive ? "text-text" : "text-text-muted line-through"
          )}
        >
          {chosen ? "🎯 " : ""}
          {e.name}
        </span>
        <span className="flex items-center gap-1 text-[9px] text-text-muted">
          {initiative !== undefined && (
            <span
              className="tabular inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-bg/60 px-1 font-bold text-gold"
              title={`Initiative ${initiative}`}
            >
              {initiative}
            </span>
          )}
          AC {e.armorClass}
        </span>
      </div>
      <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-surface-light">
        <div
          className={cn("h-full rounded-full transition-all", band.barClass)}
          style={{ width: `${Math.round(band.fill * 100)}%` }}
        />
      </div>
      <div className="mt-0.5 text-right text-[9px] font-semibold uppercase tracking-wide text-text-muted">
        {e.alive ? band.label : "Dead"}
      </div>
      {e.alive && <ConditionChips conditions={e.conditions} />}
    </button>
  );
}
