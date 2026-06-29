import type { Combatant, EnemyDto, GridState, PlayerRuntimeState } from "@/types";
import { conditionMeta, conditionBadgeColors } from "@/lib/conditions";
import { CELL, PORTRAIT } from "./constants";

/**
 * Condition badges (layer 8.5): a vertical stack of small glyph chips on each token's left
 * edge (kept clear of the kind glyph, dead marker, movement label and death-save overlay).
 * Glyph + code + native <title> tooltip — never colour alone.
 */
export default function ConditionBadges({
  grid,
  combatantByRef,
  enemyById,
  runtimeByPlayerId,
  reduced,
}: {
  grid: GridState;
  combatantByRef: Record<string, Combatant>;
  enemyById: Record<string, EnemyDto>;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  reduced: boolean;
}) {
  return (
    <g>
      {Object.entries(grid.tokens).map(([refId, tk]) => {
        const c = combatantByRef[refId];
        const isEnemy = c?.kind === "ENEMY";
        const conds = (isEnemy
          ? enemyById[refId]?.conditions
          : runtimeByPlayerId[refId]?.conditions) ?? [];
        if (conds.length === 0) return null;
        const cx = tk.x * CELL + CELL / 2;
        const cy = tk.y * CELL + CELL / 2;
        const shown = conds.slice(0, 4);
        const extra = conds.length - shown.length;
        const bx = -(PORTRAIT / 2 + 7);
        const startY = -(PORTRAIT / 2) + 7;
        return (
          <g
            key={`cond${refId}`}
            style={{
              transform: `translate(${cx}px, ${cy}px)`,
              transition: reduced
                ? undefined
                : "transform 280ms cubic-bezier(0.22, 1, 0.36, 1)",
            }}
            pointerEvents="none"
          >
            {shown.map((name, i) => {
              const meta = conditionMeta(name);
              const colors = conditionBadgeColors(meta.tone);
              return (
                <g key={name} transform={`translate(${bx} ${startY + i * 14})`}>
                  <title>{`${meta.label} — ${meta.hint}`}</title>
                  <circle r={6.5} fill={colors.fill} stroke={colors.text} strokeWidth={1} />
                  <text
                    textAnchor="middle"
                    y={2.5}
                    fontSize={7}
                    fontWeight={700}
                    fill={colors.text}
                    aria-hidden="true"
                  >
                    {meta.code}
                  </text>
                </g>
              );
            })}
            {extra > 0 && (
              <g transform={`translate(${bx} ${startY + shown.length * 14})`}>
                <title>{`${extra} more condition${extra > 1 ? "s" : ""}`}</title>
                <circle r={6.5} fill="#1a1414" stroke="#a39a93" strokeWidth={1} />
                <text
                  textAnchor="middle"
                  y={2.5}
                  fontSize={7}
                  fontWeight={700}
                  fill="#a39a93"
                  aria-hidden="true"
                >
                  {`+${extra}`}
                </text>
              </g>
            )}
          </g>
        );
      })}
    </g>
  );
}
