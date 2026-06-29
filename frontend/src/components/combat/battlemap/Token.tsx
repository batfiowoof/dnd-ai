import { cn } from "@/components/ui";
import Portrait from "@/components/Portrait";
import type { AttackMode } from "@/lib/combat";
import { PORTRAIT } from "./constants";

/** Fully-computed presentation props for a single combatant token (no derivation here). */
export interface TokenProps {
  cx: number;
  cy: number;
  reduced: boolean;
  clickable: boolean;
  ariaLabel: string;
  onActivate: () => void;
  opacity: number;
  dimOutOfRange: boolean;
  name: string;
  isEnemy: boolean;
  isActive: boolean;
  ringColor: string;
  validTarget: boolean;
  isPicked: boolean;
  targetAlly: boolean;
  atkMode: AttackMode;
  isDead: boolean;
  showMovementLabel: boolean;
  movementRemaining: number;
  mySpeed: number;
}

/** A single combatant token: shadow, glow, HP ring, portrait, enemy glyph, badges, markers. */
export default function Token({
  cx,
  cy,
  reduced,
  clickable,
  ariaLabel,
  onActivate,
  opacity,
  dimOutOfRange,
  name,
  isEnemy,
  isActive,
  ringColor,
  validTarget,
  isPicked,
  targetAlly,
  atkMode,
  isDead,
  showMovementLabel,
  movementRemaining,
  mySpeed,
}: TokenProps) {
  return (
    <g
      style={{
        // CSS transform (not the SVG attribute) so the browser tweens
        // position changes — the token slides between squares.
        transform: `translate(${cx}px, ${cy}px)`,
        transition: reduced
          ? undefined
          : "transform 280ms cubic-bezier(0.22, 1, 0.36, 1)",
      }}
      role={clickable ? "button" : "img"}
      tabIndex={clickable ? 0 : undefined}
      aria-label={ariaLabel}
      className={cn(
        "focus:outline-none",
        clickable && "cursor-pointer"
      )}
      onClick={clickable ? onActivate : undefined}
      onKeyDown={
        clickable
          ? (e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onActivate();
              }
            }
          : undefined
      }
      opacity={opacity}
    >
      {dimOutOfRange && <title>{`${name} — out of range`}</title>}
      {/* Contact shadow lifting the token off the board */}
      <ellipse
        cx={0}
        cy={PORTRAIT / 2 + 3}
        rx={PORTRAIT / 2 - 1}
        ry={4}
        fill="#000000"
        opacity={0.45}
        pointerEvents="none"
      />

      {/* Active-combatant gold glow (pulse stilled under reduced motion) */}
      {isActive && (
        <circle
          r={PORTRAIT / 2 + 5}
          fill="none"
          stroke="var(--color-gold)"
          strokeWidth={2}
          opacity={0.7}
          className={reduced ? undefined : "animate-pulse"}
        />
      )}

      {/* HP ring — band colour for enemies (HP hidden), real HP colour for players */}
      <circle
        r={PORTRAIT / 2 + 2}
        fill="none"
        stroke={ringColor}
        strokeWidth={3}
      />

      {/* Spell-targeting highlight: a valid target pulses, a picked target is solid. */}
      {(validTarget || isPicked) && (
        <circle
          r={PORTRAIT / 2 + 6}
          fill="none"
          stroke={targetAlly ? "var(--color-success)" : "var(--color-accent)"}
          strokeWidth={isPicked ? 3 : 2}
          strokeDasharray={isPicked ? undefined : "4 3"}
          opacity={isPicked ? 0.95 : 0.7}
          className={!isPicked && !reduced ? "animate-pulse" : undefined}
        />
      )}

      {/* Enemy distinguishing ring (kind not conveyed by colour alone) */}
      {isEnemy && (
        <circle
          r={PORTRAIT / 2 + 5}
          fill="none"
          stroke="var(--color-danger)"
          strokeWidth={1.5}
          strokeDasharray="3 3"
          opacity={0.8}
        />
      )}

      {/* Portrait avatar */}
      <foreignObject
        x={-PORTRAIT / 2}
        y={-PORTRAIT / 2}
        width={PORTRAIT}
        height={PORTRAIT}
      >
        <Portrait
          src={isEnemy ? null : undefined}
          name={name}
          size="sm"
          active={isActive}
        />
      </foreignObject>

      {/* Enemy kind glyph (crossed-swords badge, top-right) */}
      {isEnemy && (
        <g transform={`translate(${PORTRAIT / 2 - 2} ${-PORTRAIT / 2 + 2})`}>
          <circle r={7} fill="var(--color-danger)" />
          <text
            textAnchor="middle"
            y={3}
            fontSize={9}
            fill="#fff"
            aria-hidden="true"
          >
            ⚔
          </text>
        </g>
      )}

      {/* Advantage/disadvantage preview badge (top-left) for an attack on this enemy */}
      {atkMode !== "normal" && (
        <g transform={`translate(${-(PORTRAIT / 2) + 2} ${-PORTRAIT / 2 - 2})`}>
          <title>
            {atkMode === "advantage"
              ? "You attack this enemy with advantage"
              : "You attack this enemy with disadvantage"}
          </title>
          <rect
            x={-12}
            y={-7}
            width={24}
            height={13}
            rx={3}
            fill={atkMode === "advantage" ? "#16341f" : "#3b1414"}
            stroke={atkMode === "advantage" ? "var(--color-success)" : "var(--color-danger)"}
            strokeWidth={1}
          />
          <text
            textAnchor="middle"
            y={3}
            fontSize={8}
            fontWeight={700}
            fill={atkMode === "advantage" ? "#22c55e" : "#f87171"}
            aria-hidden="true"
          >
            {atkMode === "advantage" ? "ADV" : "DIS"}
          </text>
        </g>
      )}

      {/* Dead marker — a fallen, beyond-saving combatant (death-save
          pips / stable badge for the dying are drawn in layer 9). */}
      {isDead && (
        <text
          textAnchor="middle"
          y={4}
          fontSize={20}
          fill="var(--color-danger)"
          aria-hidden="true"
        >
          ✕
        </text>
      )}

      {/* Movement label under my active token */}
      {showMovementLabel && (
        <text
          textAnchor="middle"
          y={PORTRAIT / 2 + 14}
          className="tabular"
          fontSize={9}
          fill="var(--color-gold)"
        >
          {movementRemaining}/{mySpeed} ft
        </text>
      )}
    </g>
  );
}
