"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type {
  CombatStateDto,
  Combatant,
  PlayerRuntimeState,
  SpellSummary,
  TerrainType,
} from "@/types";
import {
  gridDistanceFeet,
  reachableSquares,
  cellKey,
  aoeCells,
} from "@/lib/dnd5e";
import { Panel, Button, Alert, Spinner, cn } from "@/components/ui";
import Portrait from "@/components/Portrait";
import DeathSaveTrack, {
  StatusBadge,
  deriveDeathStatus,
} from "@/components/combat/DeathSaveTrack";
import CombatRollFeed from "@/components/combat/CombatRollFeed";
import { conditionMeta, conditionBadgeColors } from "@/lib/conditions";
import { bandMeta } from "@/lib/health";
import {
  isAllyTargeting,
  parseRangeFeet,
  attackModePreview,
  weaponRangeFeet,
} from "@/lib/combat";
import { useSessionStore } from "@/store/sessionStore";

/** Logical cell size (px in the SVG user space). ≥44 keeps tap targets accessible. */
const CELL = 48;
/** Portrait diameter inside a token (matches Portrait `sm` = 36px). */
const PORTRAIT = 36;

/** An AoE spell awaiting placement on the board (origin captured by a board click). */
export interface PlacingSpell {
  name: string;
  level: number;
  aoeShape: string;
  aoeSize: number;
}

interface BattleMapProps {
  combat: CombatStateDto;
  myPlayerId: string;
  isMyTurn: boolean;
  /** Walk speed (ft) of the local player — preview only; from the sheet or 30. */
  mySpeed: number;
  runtimeByPlayerId: Record<string, PlayerRuntimeState>;
  onMove: (x: number, y: number) => void;
  onAttackEnemy: (enemyId: string) => void;
  connected: boolean;
  /** True for the session host — gates the battle-map background upload control. */
  isHost: boolean;
  /** Set while an AoE spell is being placed; drives the template-preview overlay. */
  placingSpell: PlacingSpell | null;
  /** Commit an AoE cast at the clicked origin cell. */
  onCastAoe: (spellName: string, spellLevel: number, x: number, y: number) => void;
  /** Abandon AoE placement mode. */
  onCancelAoe: () => void;
  /** Single/multi-target spell awaiting target selection (click tokens), or null. */
  castingSpell: SpellSummary | null;
  /** Targets picked so far (multi-target spells). */
  pickedTargets: string[];
  /** Select/toggle a target token. */
  onSelectTarget: (refId: string) => void;
  /** Upload a battle-map background (host only). Rejects with a message on 400/403/409. */
  onUploadMap: (file: File) => Promise<void>;
}

/** Column letter (A, B, C…) for an x index — used in aria-labels (e.g. "C4"). */
function colLetter(x: number): string {
  return String.fromCharCode(65 + (x % 26));
}

/** HP-ratio ring colour — mirrors CharacterStatus (green >50% / gold >25% / red). */
function hpColor(ratio: number): string {
  if (ratio > 0.5) return "var(--color-success)";
  if (ratio > 0.25) return "var(--color-gold)";
  return "var(--color-danger)";
}

/** Reduced-motion: honour the OS setting AND the in-app preference flag. */
function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    const read = () =>
      setReduced(
        !!mq?.matches ||
          document.documentElement.dataset.reduceMotion === "true"
      );
    read();
    mq?.addEventListener?.("change", read);
    return () => mq?.removeEventListener?.("change", read);
  }, []);
  return reduced;
}

const TERRAIN_LABEL: Record<TerrainType, string> = {
  WALL: "Wall",
  DIFFICULT: "Difficult terrain",
  HAZARD: "Hazard",
};

/**
 * Tactical battle grid. Renders a responsive, layered SVG board: optional
 * background image + scrim, grid lines, terrain (pattern/icon — never colour
 * alone), feature markers, the active player's reachable-move highlight, and
 * combatant tokens (Portrait + HP ring, gold ring for the active combatant,
 * distinguishing glyph for enemies). On your turn, click a reachable empty cell
 * to move or an enemy token to attack. Server stays authoritative — the
 * reachable highlight is a client mirror of the server's movement rules.
 *
 * Layer order is kept explicit so an AoE-template overlay (Phase E) and
 * death-save pips (Phase C) can slot into their reserved <g> groups.
 */
export default function BattleMap({
  combat,
  myPlayerId,
  isMyTurn,
  mySpeed,
  runtimeByPlayerId,
  onMove,
  onAttackEnemy,
  connected,
  isHost,
  placingSpell,
  onCastAoe,
  onCancelAoe,
  castingSpell,
  pickedTargets,
  onSelectTarget,
  onUploadMap,
}: BattleMapProps) {
  const reduced = usePrefersReducedMotion();
  const [hovered, setHovered] = useState<string | null>(null);
  /** Hovered origin cell while placing an AoE template ("x,y"), or null. */
  const [placeHover, setPlaceHover] = useState<string | null>(null);

  /* Host background-upload control state. */
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  // Leaving placement mode clears any lingering template hover.
  useEffect(() => {
    if (!placingSpell) setPlaceHover(null);
  }, [placingSpell]);

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file later
    if (!file) return;
    setUploadError(null);
    setUploading(true);
    try {
      await onUploadMap(file);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  const grid = combat.grid;

  // refId → combatant (name / kind) for token labelling.
  const combatantByRef = useMemo(() => {
    const m: Record<string, Combatant> = {};
    combat.order.forEach((c) => {
      m[c.refId] = c;
    });
    return m;
  }, [combat.order]);

  const activeRefId = combat.active?.refId ?? null;
  const myToken = grid?.tokens[myPlayerId] ?? null;

  // Reachable highlight (my turn only) — remaining budget = speed − used.
  const reachable = useMemo(() => {
    if (!grid || !isMyTurn || !myToken) return new Set<string>();
    const budget = mySpeed - myToken.movementUsedFeet;
    return reachableSquares(grid, myPlayerId, budget);
  }, [grid, isMyTurn, myToken, mySpeed, myPlayerId]);

  // Occupied cells (can't move onto a token).
  const occupied = useMemo(() => {
    const s = new Set<string>();
    if (grid) {
      for (const t of Object.values(grid.tokens)) s.add(cellKey(t.x, t.y));
    }
    return s;
  }, [grid]);

  // Squares within a living enemy's melee reach — tinted so the player can avoid provoking
  // opportunity attacks when moving out of them.
  const threatened = useMemo(() => {
    const set = new Set<string>();
    if (!grid) return set;
    for (const e of combat.enemies) {
      if (!e.alive) continue;
      const et = grid.tokens[e.id];
      if (!et) continue;
      const reach = e.reachFeet > 0 ? e.reachFeet : 5;
      for (let gy = 0; gy < grid.height; gy++) {
        for (let gx = 0; gx < grid.width; gx++) {
          if (gridDistanceFeet(et.x, et.y, gx, gy) <= reach) set.add(cellKey(gx, gy));
        }
      }
    }
    return set;
  }, [grid, combat.enemies]);

  // Combat is "busy" while queued action animations play or the DM narrates the beat.
  // (Subscribed before the early return below to keep hook order stable.)
  const queueLength = useSessionStore((s) => s.combatActionQueue.length);
  const combatNarrating = useSessionStore((s) => s.combatNarrating);
  const busy = queueLength > 0 || combatNarrating;

  // Resume from a pre-grid encounter → nothing to draw.
  if (!grid) return null;

  const W = grid.width * CELL;
  const H = grid.height * CELL;
  const placing = !!placingSpell;
  const targeting = !!castingSpell;
  // Suspend interactions while aiming an AoE template, or while the previous beat's action
  // animations / DM narration are still resolving (so the player can follow what happened).
  const interactive = isMyTurn && connected && !placing && !busy;
  // Active spell-targeting context (which side is valid + the spell's range in feet).
  const casterTok = grid.tokens[myPlayerId];
  const targetAlly = castingSpell ? isAllyTargeting(castingSpell) : false;
  const targetRangeFeet = castingSpell ? parseRangeFeet(castingSpell.range) : Infinity;
  // My conditions drive the advantage/disadvantage preview shown on enemy tokens.
  const myConds = runtimeByPlayerId[myPlayerId]?.conditions;
  // My basic-attack range (ft) from my weapons — enemies beyond it can't be attacked.
  const myAttackRange = weaponRangeFeet(runtimeByPlayerId[myPlayerId]?.inventory);

  const enemyById = Object.fromEntries(combat.enemies.map((e) => [e.id, e]));

  // Template cells under the cursor while placing (in-bounds keys only).
  const previewCells = (() => {
    if (!placingSpell || !placeHover) return new Set<string>();
    const [ox, oy] = placeHover.split(",").map(Number);
    const all = aoeCells(
      ox,
      oy,
      placingSpell.aoeShape,
      placingSpell.aoeSize
    );
    const inBounds = new Set<string>();
    for (const k of all) {
      const [cx, cy] = k.split(",").map(Number);
      if (cx >= 0 && cy >= 0 && cx < grid.width && cy < grid.height) {
        inBounds.add(k);
      }
    }
    return inBounds;
  })();

  return (
    <Panel className={cn("p-2 sm:p-3", !reduced && "animate-rise")}>
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="font-display text-xs font-bold uppercase tracking-wider text-accent">
          Battlefield
        </span>
        <div className="flex items-center gap-3">
          {isMyTurn && myToken && (
            <span className="tabular text-[11px] text-text-muted">
              Move{" "}
              <span className="text-gold">
                {Math.max(0, mySpeed - myToken.movementUsedFeet)}
              </span>
              /{mySpeed} ft
            </span>
          )}
          {isHost && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={handleFileChange}
              />
              <Button
                type="button"
                size="sm"
                variant="ghost"
                disabled={uploading}
                onClick={() => fileInputRef.current?.click()}
                title="Upload a battle-map background (host only)"
              >
                {uploading ? (
                  <span className="inline-flex items-center gap-1.5">
                    <Spinner className="h-3 w-3 text-gold" /> Uploading…
                  </span>
                ) : (
                  "⬆ Map"
                )}
              </Button>
            </>
          )}
        </div>
      </div>

      {uploadError && (
        <Alert className="mb-2 text-xs">{uploadError}</Alert>
      )}

      {/* AoE placement banner */}
      {placingSpell && (
        <div className="mb-2 flex items-center justify-between gap-2 rounded-lg border border-gold/50 bg-gold-muted px-3 py-1.5">
          <span className="text-xs text-gold">
            Placing{" "}
            <span className="font-semibold">{placingSpell.name}</span> — click a
            square to aim
          </span>
          <Button type="button" size="sm" variant="ghost" onClick={onCancelAoe}>
            Cancel
          </Button>
        </div>
      )}

      <div
        className="relative mx-auto w-full"
        style={{ maxWidth: Math.min(W, 640) }}
      >
        {/* Compact roll feed docked on the map (enemy/NPC/other rolls). */}
        <CombatRollFeed />
        <svg
          viewBox={`0 0 ${W} ${H}`}
          preserveAspectRatio="xMidYMid meet"
          className="block h-auto w-full select-none rounded-lg"
          role="group"
          aria-label="Tactical battle grid"
        >
          <defs>
            {/* Difficult terrain — teal hatch (distinct from the gold AoE template and red
                hazards), denser + tinted so it reads over a busy map background. */}
            <pattern
              id="bm-difficult"
              width="6"
              height="6"
              patternTransform="rotate(45)"
              patternUnits="userSpaceOnUse"
            >
              <rect width="6" height="6" fill="#2dd4bf" fillOpacity="0.22" />
              <line
                x1="0"
                y1="0"
                x2="0"
                y2="6"
                stroke="#5eead4"
                strokeWidth="2"
                strokeOpacity="0.8"
              />
            </pattern>

            {/* Subtle board vignette — darkens the edges for depth. */}
            <radialGradient id="bm-vignette" cx="50%" cy="50%" r="75%">
              <stop offset="55%" stopColor="#000000" stopOpacity={0} />
              <stop offset="100%" stopColor="#000000" stopOpacity={0.4} />
            </radialGradient>

            {/* Soft drop shadow lifting tokens off the board. */}
            <filter id="bm-token-shadow" x="-40%" y="-40%" width="180%" height="180%">
              <feDropShadow
                dx="0"
                dy="1.5"
                stdDeviation="1.6"
                floodColor="#000000"
                floodOpacity="0.55"
              />
            </filter>
          </defs>

          {/* 1 — Background image + legibility scrim */}
          {grid.backgroundImageUrl && (
            <g>
              <image
                href={grid.backgroundImageUrl}
                x={0}
                y={0}
                width={W}
                height={H}
                preserveAspectRatio="xMidYMid slice"
              />
              <rect x={0} y={0} width={W} height={H} fill="#0b0a0a" opacity={0.45} />
            </g>
          )}

          {/* 2 — Board fill (skipped when an image already covers it) */}
          {!grid.backgroundImageUrl && (
            <rect x={0} y={0} width={W} height={H} fill="var(--color-surface)" />
          )}

          {/* 2b — Vignette (depth on the board edges) */}
          <rect
            x={0}
            y={0}
            width={W}
            height={H}
            fill="url(#bm-vignette)"
            pointerEvents="none"
          />

          {/* 3 — Grid lines */}
          <g
            stroke="var(--color-border)"
            strokeWidth={1}
            opacity={0.5}
            shapeRendering="crispEdges"
          >
            {Array.from({ length: grid.width + 1 }).map((_, i) => (
              <line key={`v${i}`} x1={i * CELL} y1={0} x2={i * CELL} y2={H} />
            ))}
            {Array.from({ length: grid.height + 1 }).map((_, i) => (
              <line key={`h${i}`} x1={0} y1={i * CELL} x2={W} y2={i * CELL} />
            ))}
          </g>

          {/* 4 — Terrain (pattern + icon, never colour alone) */}
          <g>
            {grid.terrain.map((t) => {
              const tx = t.x * CELL;
              const ty = t.y * CELL;
              if (t.type === "WALL") {
                return (
                  <g key={`t${t.x}-${t.y}`}>
                    <rect
                      x={tx}
                      y={ty}
                      width={CELL}
                      height={CELL}
                      fill="var(--color-bg-elevated)"
                    />
                    {/* top/left edge highlight for a chiselled-stone read */}
                    <path
                      d={`M${tx} ${ty + CELL} L${tx} ${ty} L${tx + CELL} ${ty}`}
                      fill="none"
                      stroke="var(--color-border)"
                      strokeWidth={2}
                    />
                    {/* full outline so the wall reads even over a map image */}
                    <rect
                      x={tx + 0.5}
                      y={ty + 0.5}
                      width={CELL - 1}
                      height={CELL - 1}
                      fill="none"
                      stroke="#6b7280"
                      strokeOpacity={0.7}
                      strokeWidth={1.5}
                    />
                  </g>
                );
              }
              if (t.type === "DIFFICULT") {
                return (
                  <g key={`t${t.x}-${t.y}`}>
                    <rect x={tx} y={ty} width={CELL} height={CELL} fill="url(#bm-difficult)" />
                    <rect
                      x={tx + 0.5}
                      y={ty + 0.5}
                      width={CELL - 1}
                      height={CELL - 1}
                      fill="none"
                      stroke="#2dd4bf"
                      strokeOpacity={0.6}
                      strokeWidth={1.5}
                    />
                  </g>
                );
              }
              // HAZARD — strong red tint + outline + spike glyph.
              return (
                <g key={`t${t.x}-${t.y}`}>
                  <rect
                    x={tx}
                    y={ty}
                    width={CELL}
                    height={CELL}
                    fill="var(--color-danger)"
                    fillOpacity={0.2}
                  />
                  <rect
                    x={tx + 0.5}
                    y={ty + 0.5}
                    width={CELL - 1}
                    height={CELL - 1}
                    fill="none"
                    stroke="var(--color-danger)"
                    strokeOpacity={0.7}
                    strokeWidth={1.5}
                  />
                  <path
                    d={`M${tx + CELL / 2} ${ty + 10} L${tx + CELL - 12} ${
                      ty + CELL - 12
                    } L${tx + 12} ${ty + CELL - 12} Z`}
                    fill="var(--color-danger)"
                    opacity={0.9}
                  />
                </g>
              );
            })}
          </g>

          {/* 5 — Feature markers (gold diamond + label) */}
          <g>
            {grid.features.map((f) => {
              const fx = f.x * CELL + CELL / 2;
              const fy = f.y * CELL + CELL / 2;
              return (
                <g key={`f${f.x}-${f.y}`}>
                  <rect
                    x={fx - 5}
                    y={fy - 5}
                    width={10}
                    height={10}
                    transform={`rotate(45 ${fx} ${fy})`}
                    fill="var(--color-gold)"
                    opacity={0.85}
                  />
                  <text
                    x={fx}
                    y={fy + CELL / 2 - 4}
                    textAnchor="middle"
                    style={{ fontFamily: "var(--font-display)" }}
                    fontSize={9}
                    fill="var(--color-gold)"
                  >
                    {f.label}
                  </text>
                </g>
              );
            })}
          </g>

          {/* 5.5 — Opportunity-attack threat zones (enemy melee reach) on the player's turn */}
          {isMyTurn && !placing && (
            <g aria-hidden="true" pointerEvents="none">
              {[...threatened].map((k) => {
                const [tx, ty] = k.split(",").map(Number);
                return (
                  <rect
                    key={`threat${k}`}
                    x={tx * CELL + 1}
                    y={ty * CELL + 1}
                    width={CELL - 2}
                    height={CELL - 2}
                    rx={3}
                    fill="var(--color-danger)"
                    fillOpacity={0.06}
                    stroke="var(--color-danger)"
                    strokeOpacity={0.25}
                    strokeDasharray="2 4"
                    strokeWidth={1}
                  />
                );
              })}
            </g>
          )}

          {/* 6 — Reachable-move highlight (active player's turn only; hidden while targeting) */}
          {interactive && !targeting && (
            <g>
              {[...reachable].map((k) => {
                if (occupied.has(k)) return null;
                const [cx, cy] = k.split(",").map(Number);
                const isHover = hovered === k;
                return (
                  <g
                    key={`r${k}`}
                    role="button"
                    tabIndex={0}
                    aria-label={`Move to ${colLetter(cx)}${cy + 1} (${gridDistanceFeet(
                      myToken!.x,
                      myToken!.y,
                      cx,
                      cy
                    )} ft)`}
                    className="cursor-pointer focus:outline-none"
                    onClick={() => onMove(cx, cy)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        onMove(cx, cy);
                      }
                    }}
                    onMouseEnter={() => setHovered(k)}
                    onMouseLeave={() => setHovered((h) => (h === k ? null : h))}
                    onFocus={() => setHovered(k)}
                    onBlur={() => setHovered((h) => (h === k ? null : h))}
                  >
                    <rect
                      x={cx * CELL + 2}
                      y={cy * CELL + 2}
                      width={CELL - 4}
                      height={CELL - 4}
                      rx={4}
                      fill="var(--color-accent-glow)"
                      stroke="var(--color-accent)"
                      strokeOpacity={isHover ? 0.9 : 0.35}
                      strokeWidth={isHover ? 2 : 1}
                      className={reduced ? undefined : "transition-all"}
                    />
                    {isHover && (
                      <text
                        x={cx * CELL + CELL / 2}
                        y={cy * CELL + CELL / 2 + 3}
                        textAnchor="middle"
                        className="tabular pointer-events-none"
                        fontSize={10}
                        fill="var(--color-accent-light)"
                      >
                        {gridDistanceFeet(myToken!.x, myToken!.y, cx, cy)}ft
                      </text>
                    )}
                  </g>
                );
              })}
            </g>
          )}

          {/* 7 — AoE template preview (placement mode) */}
          {placing && (
            <g aria-hidden="true" pointerEvents="none">
              {[...previewCells].map((k) => {
                const [cx, cy] = k.split(",").map(Number);
                const isOrigin = k === placeHover;
                return (
                  <rect
                    key={`aoe${k}`}
                    x={cx * CELL + 1}
                    y={cy * CELL + 1}
                    width={CELL - 2}
                    height={CELL - 2}
                    rx={3}
                    fill="var(--color-gold)"
                    fillOpacity={isOrigin ? 0.42 : 0.26}
                    stroke="var(--color-gold)"
                    strokeOpacity={0.85}
                    strokeWidth={isOrigin ? 2 : 1}
                  />
                );
              })}
            </g>
          )}

          {/* 8 — Tokens */}
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
                : hpColor(ratio);
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

              return (
                <g
                  key={`tok${refId}`}
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
                  aria-label={
                    validTarget
                      ? `Target ${label}`
                      : canAttack
                        ? `Attack ${label}`
                        : label
                  }
                  className={cn(
                    "focus:outline-none",
                    clickable && "cursor-pointer"
                  )}
                  onClick={clickable ? activate : undefined}
                  onKeyDown={
                    clickable
                      ? (e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            activate();
                          }
                        }
                      : undefined
                  }
                  opacity={
                    downed ? 0.45 : dimForTargeting || dimOutOfRange ? 0.45 : 1
                  }
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
                  {status === "DEAD" && (
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
                  {isActive && refId === myPlayerId && (
                    <text
                      textAnchor="middle"
                      y={PORTRAIT / 2 + 14}
                      className="tabular"
                      fontSize={9}
                      fill="var(--color-gold)"
                    >
                      {Math.max(0, mySpeed - tk.movementUsedFeet)}/{mySpeed} ft
                    </text>
                  )}
                </g>
              );
            })}
          </g>

          {/* 8.5 — Condition badges: a vertical stack of small glyph chips on each token's
                 left edge (kept clear of the kind glyph, dead marker, movement label and
                 death-save overlay). Glyph + code + native <title> tooltip — never colour alone. */}
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

          {/* 9 — Death-save overlay (full opacity above the faded downed tokens):
                 dying allies show their save pips, the stable show a gold badge. */}
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

          {/* 10 — AoE placement capture layer (transparent cells on top) */}
          {placing && (
            <g>
              {Array.from({ length: grid.height }).map((_, cy) =>
                Array.from({ length: grid.width }).map((_, cx) => {
                  const k = cellKey(cx, cy);
                  return (
                    <rect
                      key={`aim${k}`}
                      x={cx * CELL}
                      y={cy * CELL}
                      width={CELL}
                      height={CELL}
                      fill="transparent"
                      role="button"
                      tabIndex={0}
                      aria-label={`Aim ${placingSpell!.name} at ${colLetter(
                        cx
                      )}${cy + 1}`}
                      className="cursor-crosshair focus:outline-none"
                      onMouseEnter={() => setPlaceHover(k)}
                      onMouseLeave={() =>
                        setPlaceHover((h) => (h === k ? null : h))
                      }
                      onFocus={() => setPlaceHover(k)}
                      onClick={() =>
                        connected &&
                        onCastAoe(
                          placingSpell!.name,
                          placingSpell!.level,
                          cx,
                          cy
                        )
                      }
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          if (connected) {
                            onCastAoe(
                              placingSpell!.name,
                              placingSpell!.level,
                              cx,
                              cy
                            );
                          }
                        }
                      }}
                    />
                  );
                })
              )}
            </g>
          )}
        </svg>
      </div>

      {/* Terrain legend */}
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-text-muted">
        <LegendSwatch type="WALL" />
        <LegendSwatch type="DIFFICULT" />
        <LegendSwatch type="HAZARD" />
        <span className="inline-flex items-center gap-1">
          <span className="inline-block h-2.5 w-2.5 rounded-full border-2 border-[var(--color-gold)]" />
          Feature
        </span>
      </div>
    </Panel>
  );
}

/** A small inline terrain key swatch + label (matches the SVG treatment). */
function LegendSwatch({ type }: { type: TerrainType }) {
  return (
    <span className="inline-flex items-center gap-1">
      <svg width={14} height={14} className="rounded-sm" aria-hidden="true">
        {type === "WALL" && (
          <>
            <rect width={14} height={14} fill="var(--color-bg-elevated)" />
            <path
              d="M0 14 L0 0 L14 0"
              fill="none"
              stroke="var(--color-border)"
              strokeWidth={2}
            />
          </>
        )}
        {type === "DIFFICULT" && (
          <>
            <rect width={14} height={14} fill="#2dd4bf" fillOpacity={0.22} />
            <path
              d="M-2 4 L4 -2 M-2 12 L12 -2 M2 16 L16 2"
              stroke="#5eead4"
              strokeWidth={2}
              strokeOpacity={0.8}
            />
            <rect
              x={0.5}
              y={0.5}
              width={13}
              height={13}
              fill="none"
              stroke="#2dd4bf"
              strokeOpacity={0.6}
            />
          </>
        )}
        {type === "HAZARD" && (
          <>
            <rect width={14} height={14} fill="var(--color-danger)" fillOpacity={0.2} />
            <rect
              x={0.5}
              y={0.5}
              width={13}
              height={13}
              fill="none"
              stroke="var(--color-danger)"
              strokeOpacity={0.7}
            />
            <path d="M7 2 L12 12 L2 12 Z" fill="var(--color-danger)" />
          </>
        )}
      </svg>
      {TERRAIN_LABEL[type]}
    </span>
  );
}
