import type { Combatant, GridState, PlayerRuntimeState } from "@/types";
import DeathSaveTrack, {
  StatusBadge,
  deriveDeathStatus,
} from "@/components/combat/DeathSaveTrack";
import { CELL, PORTRAIT } from "./constants";

/**
 * Death-save overlay (layer 9, full opacity above the faded downed tokens): dying allies show
 * their save pips, the stable show a gold badge.
 */
export default function DeathSaveOverlay({
  grid,
  combatantByRef,
  runtimeByPlayerId,
}: {
  grid: GridState;
  combatantByRef: Record<string, Combatant>;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
}) {
  return (
    <g>
      {Object.entries(grid.tokens).map(([refId, tk]) => {
        const c = combatantByRef[refId];
        if (c?.kind === "ENEMY") return null;
        const runtime = runtimeByPlayerId[refId];
        const status = deriveDeathStatus(runtime);
        if (status !== "DYING" && status !== "STABLE") return null;
        const cx = tk.x * CELL + CELL / 2;
        const oy = tk.y * CELL + CELL / 2 - (PORTRAIT / 2 + 20);
        return (
          <foreignObject
            key={`ds${refId}`}
            x={cx - 44}
            y={oy}
            width={88}
            height={18}
            className="overflow-visible"
            style={{ pointerEvents: "none" }}
          >
            <div className="flex justify-center">
              <div className="rounded bg-bg/85 px-1 py-0.5 shadow-[0_1px_4px_rgba(0,0,0,0.6)]">
                {status === "DYING" ? (
                  <DeathSaveTrack
                    successes={runtime?.deathSaveSuccesses ?? 0}
                    failures={runtime?.deathSaveFailures ?? 0}
                    size="xs"
                  />
                ) : (
                  <StatusBadge status="STABLE" />
                )}
              </div>
            </div>
          </foreignObject>
        );
      })}
    </g>
  );
}
