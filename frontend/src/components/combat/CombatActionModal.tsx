"use client";

import { useEffect, useRef, useState } from "react";
import { Modal, cn } from "@/components/ui";
import { useSessionStore } from "@/store/sessionStore";
import Die from "@/components/dice/Die";
import type { CombatActionEvent, CombatTarget } from "@/types";

type Phase = "rolling" | "revealed";

const ROLL_MS = 650;
const BASE_LINGER_MS = 1400;
const PER_TARGET_MS = 320;

function reducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
  );
}

/** The d20 roll shown for a target, if any (attack roll, else save roll). */
function primaryRoll(t: CombatTarget) {
  return t.attackRoll ?? t.saveRoll ?? null;
}

/** One-line outcome label for a target row. */
function outcome(t: CombatTarget): { text: string; tone: string } {
  if (t.heal != null) return { text: `+${t.heal} HP`, tone: "text-emerald-400" };
  if (t.saveRoll) {
    return t.saved
      ? { text: "Saved", tone: "text-text-muted" }
      : { text: "Failed", tone: "text-accent" };
  }
  if (t.hit === false) return { text: "Miss", tone: "text-text-muted" };
  if (t.attackRoll?.crit) return { text: "Critical!", tone: "text-accent" };
  if (t.hit) return { text: "Hit", tone: "text-accent" };
  if (t.condition) return { text: t.condition, tone: "text-gold" };
  return { text: "", tone: "" };
}

/**
 * Plays back combat actions ONE AT A TIME from the store's FIFO queue. When the head
 * action finishes animating it is dequeued, revealing the next — so the player sees
 * their own attack first, then each enemy's turn in initiative order (fixing the old
 * single-slot bug where only the last enemy hit was ever shown). Handles attacks,
 * multiattack / AoE (several targets), saves, and heals.
 */
export default function CombatActionModal() {
  const head = useSessionStore((s) => s.combatActionQueue[0] ?? null);
  const dequeue = useSessionStore((s) => s.dequeueCombatAction);

  const [evt, setEvt] = useState<(CombatActionEvent & { eventId: string }) | null>(null);
  const [phase, setPhase] = useState<Phase>("rolling");
  const [dieFace, setDieFace] = useState(1);
  const seen = useRef<string | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const intervals = useRef<ReturnType<typeof setInterval>[]>([]);

  function clearAll() {
    timers.current.forEach(clearTimeout);
    intervals.current.forEach(clearInterval);
    timers.current = [];
    intervals.current = [];
  }

  useEffect(() => {
    if (!head || head.eventId === seen.current) return;
    seen.current = head.eventId;
    clearAll();
    setEvt(head);

    const lead = head.targets[0] ? primaryRoll(head.targets[0]) : null;
    const finalFace = lead?.faces[0] ?? lead?.total ?? 0;
    const lingerMs = BASE_LINGER_MS + PER_TARGET_MS * Math.max(0, head.targets.length - 1);

    const finish = () => {
      timers.current.push(
        setTimeout(() => {
          setEvt(null);
          dequeue(); // advance to the next queued action
        }, lingerMs)
      );
    };

    if (reducedMotion() || !lead) {
      setDieFace(finalFace || 1);
      setPhase("revealed");
      finish();
      return;
    }

    setPhase("rolling");
    const cyc = setInterval(() => setDieFace(Math.floor(Math.random() * 20) + 1), 70);
    intervals.current.push(cyc);
    timers.current.push(
      setTimeout(() => {
        clearInterval(cyc);
        setDieFace(finalFace || 1);
        setPhase("revealed");
        finish();
      }, ROLL_MS)
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [head]);

  useEffect(() => () => clearAll(), []);

  if (!evt) return null;

  const lead = evt.targets[0] ? primaryRoll(evt.targets[0]) : null;
  const isHeal = evt.actionKind === "SPELL_HEAL";

  return (
    <Modal open={!!evt} onClose={() => { setEvt(null); dequeue(); }} title="Combat" size="sm">
      <div className="flex flex-col items-center gap-3 text-center">
        <p className="text-sm">
          <span className="font-display font-bold text-accent">{evt.actorName}</span>{" "}
          <span className="text-text-muted">{evt.label}</span>
        </p>

        {lead && (
          <Die
            value={dieFace}
            sides={20}
            rolling={phase === "rolling"}
            crit={phase === "revealed" && !!lead.crit}
            fumble={phase === "revealed" && !!lead.fumble}
          />
        )}

        {phase === "revealed" && (
          <div className="flex w-full flex-col gap-1.5 animate-rise">
            {evt.targets.map((t, i) => {
              const o = outcome(t);
              const roll = primaryRoll(t);
              return (
                <div
                  key={`${t.targetName}-${i}`}
                  className="flex items-center justify-between gap-2 rounded-md border border-border bg-surface px-2.5 py-1.5 text-xs"
                >
                  <span className="truncate font-semibold text-text">{t.targetName}</span>
                  <span className="flex items-center gap-2">
                    {roll && (
                      <span className="font-mono text-[10px] text-text-muted">
                        {roll.total}
                        {t.vsAc != null ? ` vs AC ${t.vsAc}` : ""}
                        {t.saveDc != null ? ` vs DC ${t.saveDc}` : ""}
                      </span>
                    )}
                    {o.text && (
                      <span className={cn("font-display font-bold uppercase tracking-wide", o.tone)}>
                        {o.text}
                      </span>
                    )}
                    {t.damageRoll && (
                      <span className="font-display font-bold text-danger">
                        {t.damageRoll.total}
                      </span>
                    )}
                    <span className="tabular text-[10px] text-text-muted">
                      {Math.max(0, t.currentHp)}/{t.maxHp}
                    </span>
                  </span>
                </div>
              );
            })}
          </div>
        )}

        {phase === "revealed" && evt.targets.some((t) => t.defeated) && (
          <p className="text-[10px] font-semibold uppercase tracking-widest text-danger">
            {evt.targets.filter((t) => t.defeated).map((t) => t.targetName).join(", ")} defeated!
          </p>
        )}

        {phase === "revealed" && isHeal && (
          <p className="text-[10px] text-emerald-400">
            {evt.actorName} mends the party.
          </p>
        )}
      </div>
    </Modal>
  );
}
