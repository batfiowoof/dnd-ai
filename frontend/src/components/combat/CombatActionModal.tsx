"use client";

import { useEffect, useRef, useState } from "react";
import { Modal, Button, cn } from "@/components/ui";
import { useSessionStore } from "@/store/sessionStore";
import Die from "@/components/dice/Die";
import type { CombatActionEvent, CombatTarget } from "@/types";
import { conditionMeta } from "@/lib/conditions";
import { formatDamageRoll } from "@/lib/combat";
import { bandMeta } from "@/lib/health";

// "idle" → the local player's own action, waiting for them to tap "Roll d20" (enemy/other
// actions skip straight to "rolling"); "rolling" → the d20 tumbles; "revealed" → result held.
type Phase = "idle" | "rolling" | "revealed";

const ROLL_MS = 650;
// Dwell long enough that each action (the player's, then every enemy in initiative order) is
// readable rather than flashing past — kept calm so a string of enemy turns doesn't overwhelm.
// Click the modal (once revealed) to fast-forward to the next action.
const BASE_LINGER_MS = 3500;
const PER_TARGET_MS = 500;

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
  if (t.condition) return { text: conditionMeta(t.condition).label, tone: "text-gold" };
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
  const pushFeed = useSessionStore((s) => s.pushCombatFeed);
  const myName = useSessionStore(
    (s) => s.combat?.order.find((c) => c.refId === s.myPlayerId)?.name ?? null
  );

  const [evt, setEvt] = useState<(CombatActionEvent & { eventId: string }) | null>(null);
  const [phase, setPhase] = useState<Phase>("rolling");
  const [dieFace, setDieFace] = useState(1);
  const seen = useRef<string | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const intervals = useRef<ReturnType<typeof setInterval>[]>([]);
  // The deferred roll starter for the local player's OWN action (run when they tap "Roll d20").
  const startRoll = useRef<(() => void) | null>(null);

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
    startRoll.current = null;
    setEvt(head);

    const lead = head.targets[0] ? primaryRoll(head.targets[0]) : null;
    const finalFace = lead?.faces[0] ?? lead?.total ?? 0;
    const lingerMs = BASE_LINGER_MS + PER_TARGET_MS * Math.max(0, head.targets.length - 1);

    // Reveal: surface this action's line in the corner feed in step with the modal (paced
    // playback), then hold it for `lingerMs` before advancing to the next queued action.
    const reveal = () => {
      pushFeed(head);
      timers.current.push(
        setTimeout(() => {
          setEvt(null);
          dequeue();
        }, lingerMs)
      );
    };

    const run = () => {
      if (reducedMotion() || !lead) {
        setDieFace(finalFace || 1);
        setPhase("revealed");
        reveal();
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
          reveal();
        }, ROLL_MS)
      );
    };

    // The local player taps to roll their OWN attack/cast; enemy and other-player actions
    // auto-play. Identify "mine" by matching the actor to my character in the initiative order.
    const isOwnAction =
      head.actorKind === "PLAYER" && !!myName && head.actorName === myName && !!lead;
    if (isOwnAction) {
      setDieFace(1);
      setPhase("idle");
      startRoll.current = run;
    } else {
      run();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [head, myName]);

  useEffect(() => () => clearAll(), []);

  if (!evt) return null;

  const lead = evt.targets[0] ? primaryRoll(evt.targets[0]) : null;
  const isHeal = evt.actionKind === "SPELL_HEAL";
  const idle = phase === "idle";

  return (
    <Modal
      open={!!evt}
      // While waiting on the player's own roll the modal is locked — they can't skip their
      // own dice. Once it's rolling/revealed, backdrop/ESC fast-forwards to the next action.
      onClose={() => { setEvt(null); dequeue(); }}
      dismissible={!idle}
      hideClose={idle}
      title="Combat"
      size="sm"
    >
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

        {idle && (
          <div className="flex w-full flex-col items-center gap-2 animate-rise">
            <Button
              variant="primary"
              size="lg"
              fullWidth
              onClick={() => startRoll.current?.()}
              className="active:scale-95"
              autoFocus
            >
              Roll d20
            </Button>
            <p className="text-[10px] uppercase tracking-widest text-text-muted">
              Your move — tap to roll
            </p>
          </div>
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
                    {t.damageRoll &&
                      (evt.actorKind === "ENEMY" ? (
                        // Enemy damage dice are hidden — show only the total dealt.
                        <span className="font-display font-bold text-danger">
                          {t.damageRoll.total}
                        </span>
                      ) : (
                        // The player's own damage shows the full roll: notation + per-die faces.
                        <span className="flex items-center gap-1 font-mono text-[10px] text-danger">
                          <span className="text-text-muted">{t.damageRoll.notation}</span>
                          <span className="tabular font-semibold">
                            {formatDamageRoll(t.damageRoll)}
                          </span>
                        </span>
                      ))}
                    <span className="text-[10px] text-text-muted">
                      {t.healthBand ? (
                        <span className="uppercase tracking-wide">
                          {bandMeta(t.healthBand).label}
                        </span>
                      ) : (
                        <span className="tabular">
                          {Math.max(0, t.currentHp)}/{t.maxHp}
                        </span>
                      )}
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
