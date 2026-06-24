"use client";

import { useEffect, useRef, useState } from "react";
import { Modal, cn } from "@/components/ui";
import { useSessionStore } from "@/store/sessionStore";
import Die from "@/components/dice/Die";
import type { EnemyActionEvent } from "@/types";

type Phase = "attack" | "result" | "damage" | "done";

const ATTACK_MS = 700;
const RESULT_MS = 900;
const DAMAGE_MS = 600;
const LINGER_MS = 1800;

function reducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
  );
}

/**
 * Sequences a single combat action from the composite ENEMY_ACTION event:
 * attack die → "vs AC → Hit/Miss" → damage dice → defeated flourish. Self-contained
 * so no competing DICE_ROLL broadcasts are needed during combat.
 */
export default function EnemyActionModal() {
  const lastEnemyAction = useSessionStore((s) => s.lastEnemyAction);

  const [evt, setEvt] = useState<EnemyActionEvent | null>(null);
  const [phase, setPhase] = useState<Phase>("attack");
  const [attackFace, setAttackFace] = useState(1);
  const seen = useRef<string | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const intervals = useRef<ReturnType<typeof setInterval>[]>([]);

  function clearTimers() {
    timers.current.forEach(clearTimeout);
    intervals.current.forEach(clearInterval);
    timers.current = [];
    intervals.current = [];
  }

  useEffect(() => {
    if (!lastEnemyAction || lastEnemyAction.eventId === seen.current) return;
    seen.current = lastEnemyAction.eventId;
    clearTimers();

    const action = lastEnemyAction;
    setEvt(action);

    const finalAttack = action.attackRoll.faces[0] ?? action.attackRoll.total;

    if (reducedMotion()) {
      setAttackFace(finalAttack);
      setPhase(action.hit ? "damage" : "result");
      setPhase("done");
      timers.current.push(setTimeout(() => setEvt(null), LINGER_MS));
      return;
    }

    // Phase 1: attack die tumbles
    setPhase("attack");
    const cyc = setInterval(
      () => setAttackFace(Math.floor(Math.random() * 20) + 1),
      80
    );
    intervals.current.push(cyc);

    timers.current.push(
      setTimeout(() => {
        clearInterval(cyc);
        setAttackFace(finalAttack);
        setPhase("result");
      }, ATTACK_MS)
    );

    // Phase 2/3: damage (only if hit)
    if (action.hit && action.damageRoll) {
      timers.current.push(
        setTimeout(() => setPhase("damage"), ATTACK_MS + RESULT_MS)
      );
      timers.current.push(
        setTimeout(
          () => setPhase("done"),
          ATTACK_MS + RESULT_MS + DAMAGE_MS
        )
      );
      timers.current.push(
        setTimeout(
          () => setEvt(null),
          ATTACK_MS + RESULT_MS + DAMAGE_MS + LINGER_MS
        )
      );
    } else {
      timers.current.push(
        setTimeout(() => setPhase("done"), ATTACK_MS + RESULT_MS)
      );
      timers.current.push(
        setTimeout(() => setEvt(null), ATTACK_MS + RESULT_MS + LINGER_MS)
      );
    }
  }, [lastEnemyAction]);

  useEffect(() => () => clearTimers(), []);

  if (!evt) return null;

  const showDamage =
    evt.hit &&
    evt.damageRoll &&
    (phase === "damage" || phase === "done");

  return (
    <Modal
      open={!!evt}
      onClose={() => setEvt(null)}
      title="Combat"
      size="sm"
    >
      <div className="flex flex-col items-center gap-3 text-center">
        <p className="text-sm">
          <span className="font-display font-bold text-accent">
            {evt.attackerName}
          </span>{" "}
          attacks{" "}
          <span className="font-display font-bold text-text">
            {evt.targetName}
          </span>
        </p>

        {/* Attack die */}
        <Die
          value={attackFace}
          sides={20}
          rolling={phase === "attack"}
          crit={phase !== "attack" && evt.attackRoll.crit}
          fumble={phase !== "attack" && evt.attackRoll.fumble}
        />

        {/* Attack result */}
        {phase !== "attack" && (
          <div className="animate-rise">
            <p className="text-xs text-text-muted">
              {evt.attackRoll.notation} ={" "}
              <span className="font-mono text-text">{evt.attackRoll.total}</span>{" "}
              vs AC {evt.vsAc}
            </p>
            <p
              className={cn(
                "font-display text-lg font-bold uppercase tracking-wide",
                evt.hit ? "text-accent" : "text-text-muted"
              )}
            >
              {evt.attackRoll.crit
                ? "Critical Hit!"
                : evt.attackRoll.fumble
                  ? "Fumble!"
                  : evt.hit
                    ? "Hit!"
                    : "Miss"}
            </p>
          </div>
        )}

        {/* Damage */}
        {showDamage && evt.damageRoll && (
          <div className="animate-rise flex flex-col items-center gap-1">
            <div className="flex flex-wrap items-center justify-center gap-1.5">
              {evt.damageRoll.faces.map((f, i) => (
                <Die
                  key={i}
                  value={f}
                  sides={0}
                  rolling={phase === "damage"}
                  size={44}
                />
              ))}
            </div>
            {phase === "done" && (
              <p className="font-display text-2xl font-bold text-danger">
                {evt.damageRoll.total} damage
              </p>
            )}
          </div>
        )}

        {phase === "done" && evt.targetDefeated && (
          <p className="text-xs font-semibold uppercase tracking-widest text-danger">
            {evt.targetName} is defeated!
          </p>
        )}

        {phase === "done" && evt.hit && (
          <p className="text-[10px] text-text-muted">
            {evt.targetName}: {Math.max(0, evt.targetCurrentHp)}/{evt.targetMaxHp} HP
          </p>
        )}
      </div>
    </Modal>
  );
}
