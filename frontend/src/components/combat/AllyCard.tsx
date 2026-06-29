import type { PlayerRuntimeState } from "@/types";
import { Button, Tooltip, HpBar, cn } from "@/components/ui";
import DeathSaveTrack, {
  StatusBadge,
  type DeathStatus,
} from "@/components/combat/DeathSaveTrack";
import { ConditionChips } from "@/components/combat/atoms";
import type { AllyTarget } from "@/components/combat/CombatTracker";

interface AllyCardProps {
  ally: AllyTarget;
  /** Per-player runtime — drives death-save pips / badges / conditions. */
  runtime: PlayerRuntimeState | undefined;
  /** Derived death-save status, or null when conscious. */
  status: DeathStatus | null;
  /** Can be picked as a heal/buff target while casting. */
  selectable: boolean;
  /** Already picked as a target. */
  chosen: boolean;
  /** Can be stabilized right now (dying ally, your turn, action free). */
  canStabilize: boolean;
  /** This ally is the local player. */
  isMe: boolean;
  connected: boolean;
  onSelect: () => void;
  onStabilize: () => void;
}

/** One party member in the combat tracker grid: HP / death saves, conditions, stabilize. */
export default function AllyCard({
  ally: a,
  runtime: rs,
  status,
  selectable,
  chosen,
  canStabilize,
  isMe,
  connected,
  onSelect,
  onStabilize,
}: AllyCardProps) {
  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        disabled={!selectable}
        onClick={() => selectable && onSelect()}
        data-spotlight=""
        className={cn(
          "spotlight rounded-lg border p-2 text-left transition",
          chosen
            ? "border-emerald-400 bg-emerald-400/10 shadow-[0_0_16px_rgba(52,211,153,0.25)]"
            : selectable
              ? "cursor-pointer border-emerald-400/50 bg-surface hover:border-emerald-400"
              : status === "DYING"
                ? "border-danger/40 bg-surface/60"
                : "border-border bg-surface/60"
        )}
        title={selectable ? `Target ${a.name}` : a.name}
      >
        <div className="flex items-center justify-between gap-1">
          <span className="truncate text-xs font-semibold text-text">
            {chosen ? "✚ " : ""}
            {a.name}
            {isMe ? " (you)" : ""}
          </span>
          {status && <StatusBadge status={status} />}
        </div>
        <ConditionChips conditions={rs?.conditions} />
        {status ? (
          <div className="mt-1.5 flex items-center justify-between gap-2">
            {status === "DEAD" ? (
              <span className="text-[9px] uppercase tracking-wider text-text-muted">
                Beyond saving
              </span>
            ) : (
              <DeathSaveTrack
                successes={rs?.deathSaveSuccesses ?? 0}
                failures={rs?.deathSaveFailures ?? 0}
              />
            )}
            <span className="tabular text-[9px] text-text-muted">
              0/{a.maxHp}
            </span>
          </div>
        ) : (
          <>
            <HpBar current={a.currentHp} max={a.maxHp} size="sm" className="mt-1" />
            <div className="mt-0.5 text-right text-[9px] tabular text-text-muted">
              {Math.max(0, a.currentHp)}/{a.maxHp}
            </div>
          </>
        )}
      </button>
      {canStabilize && (
        <Tooltip
          placement="top"
          className="w-full"
          content={
            <span className="block max-w-[12rem] text-xs text-text-muted">
              <span className="font-semibold text-gold">Stabilize</span> — DC 10
              Medicine check to stabilize {a.name}. Uses your action.
            </span>
          }
        >
          <Button
            type="button"
            size="sm"
            variant="ghost"
            fullWidth
            disabled={!connected}
            onClick={onStabilize}
          >
            ✚ Stabilize
          </Button>
        </Tooltip>
      )}
    </div>
  );
}
