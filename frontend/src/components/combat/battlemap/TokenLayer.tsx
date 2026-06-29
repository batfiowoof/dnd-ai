import type { Combatant, EnemyDto, GridState, PlayerRuntimeState, Token as TokenState } from "@/types";
import { colLetter, gridDistanceFeet } from "@/lib/grid";
import { attackModePreview } from "@/lib/combat";
import { bandMeta, hpColorVar } from "@/lib/health";
import { deriveDeathStatus } from "@/components/combat/DeathSaveTrack";
import { CELL } from "./constants";
import Token from "./Token";

/** Combatant tokens (layer 8): derives each token's presentation, renders a {@link Token}. */
export default function TokenLayer({
  grid,
  combatantByRef,
  activeRefId,
  enemyById,
  runtimeByPlayerId,
  casterTok,
  myAttackRange,
  targeting,
  interactive,
  targetAlly,
  targetRangeFeet,
  myConds,
  pickedTargets,
  reduced,
  myPlayerId,
  mySpeed,
  onAttackEnemy,
  onSelectTarget,
}: {
  grid: GridState;
  combatantByRef: Record<string, Combatant>;
  activeRefId: string | null;
  enemyById: Record<string, EnemyDto>;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  casterTok: TokenState | undefined;
  myAttackRange: number;
  targeting: boolean;
  interactive: boolean;
  targetAlly: boolean;
  targetRangeFeet: number;
  myConds: string[] | undefined;
  pickedTargets: string[];
  reduced: boolean;
  myPlayerId: string;
  mySpeed: number;
  onAttackEnemy: (enemyId: string) => void;
  onSelectTarget: (refId: string) => void;
}) {
  return (
    <g>
      {Object.entries(grid.tokens).map(([refId, tk]) => {
        const c = combatantByRef[refId];
        const isEnemy = c?.kind === "ENEMY";
        const isActive = refId === activeRefId;
        const cx = tk.x * CELL + CELL / 2;
        const cy = tk.y * CELL + CELL / 2;

        const enemy = isEnemy ? enemyById[refId] : undefined;
        const runtime = !isEnemy ? runtimeByPlayerId[refId] : undefined;
        // Enemies expose only a health band (exact HP hidden); players use real HP.
        const band = isEnemy ? bandMeta(enemy?.healthBand) : null;
        const cur = runtime?.currentHp ?? 0;
        const max = runtime?.maxHp ?? 1;
        const ratio = max > 0 ? cur / max : 0;
        const ringColor = isEnemy
          ? band?.color ?? "#6b7280"
          : hpColorVar(ratio);
        const status = !isEnemy ? deriveDeathStatus(runtime) : null;
        const downed = !isEnemy && runtime ? runtime.currentHp <= 0 : false;
        const name = c?.name ?? enemy?.name ?? "Combatant";

        // Plain attack is available only when NOT in spell-targeting mode and the enemy
        // is within the player's weapon range.
        const distToTok = casterTok
          ? gridDistanceFeet(casterTok.x, casterTok.y, tk.x, tk.y)
          : 0;
        const inAttackRange = !casterTok || distToTok <= myAttackRange;
        const enemyOnMyTurn =
          interactive && !targeting && isEnemy && (enemy?.alive ?? true);
        const canAttack = enemyOnMyTurn && inAttackRange;
        // Out-of-range attackable enemy → dim it so the player sees it's unreachable.
        const dimOutOfRange = enemyOnMyTurn && !inAttackRange;

        // Spell targeting: in range + the right side (allies for heal/buff, enemies else).
        const inRange =
          !casterTok ||
          gridDistanceFeet(casterTok.x, casterTok.y, tk.x, tk.y) <=
            targetRangeFeet;
        const validTarget =
          targeting &&
          interactive &&
          inRange &&
          (targetAlly ? !isEnemy : isEnemy && (enemy?.alive ?? true));
        const isPicked = pickedTargets.includes(refId);
        // Dim out-of-range / wrong-side tokens while targeting so legal picks stand out.
        const dimForTargeting = targeting && !validTarget && !isPicked;

        // Advantage/disadvantage preview for a WEAPON attack against this enemy (before the
        // roll). Limited to weapon attacks: spell attacks vs prone depend on the spell's
        // range and save spells have no attack roll, so a preview there would mislead.
        const meleeReachToTok = casterTok
          ? gridDistanceFeet(casterTok.x, casterTok.y, tk.x, tk.y) <= 5
          : true;
        const atkMode = canAttack
          ? attackModePreview(myConds, enemy?.conditions, meleeReachToTok)
          : "normal";
        const clickable = canAttack || validTarget;
        const activate = () => {
          if (validTarget) onSelectTarget(refId);
          else if (canAttack) onAttackEnemy(refId);
        };

        const label = `${name}, ${
          isEnemy ? band?.label ?? "" : `${Math.max(0, cur)}/${max} HP`
        }, ${colLetter(tk.x)}${tk.y + 1}${isEnemy ? " (enemy)" : ""}${
          isActive ? " — active" : ""
        }`;

        const ariaLabel = validTarget
          ? `Target ${label}`
          : canAttack
            ? `Attack ${label}`
            : label;
        const opacity = downed
          ? 0.45
          : dimForTargeting || dimOutOfRange
            ? 0.45
            : 1;

        return (
          <Token
            key={`tok${refId}`}
            cx={cx}
            cy={cy}
            reduced={reduced}
            clickable={clickable}
            ariaLabel={ariaLabel}
            onActivate={activate}
            opacity={opacity}
            dimOutOfRange={dimOutOfRange}
            name={name}
            isEnemy={isEnemy}
            isActive={isActive}
            ringColor={ringColor}
            validTarget={validTarget}
            isPicked={isPicked}
            targetAlly={targetAlly}
            atkMode={atkMode}
            isDead={status === "DEAD"}
            showMovementLabel={isActive && refId === myPlayerId}
            movementRemaining={Math.max(0, mySpeed - tk.movementUsedFeet)}
            mySpeed={mySpeed}
          />
        );
      })}
    </g>
  );
}
