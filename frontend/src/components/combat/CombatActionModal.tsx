"use client";

import { useEffect, useRef, useState } from "react";
import { Modal, Button, Spinner, cn } from "@/components/ui";
import { useSessionStore } from "@/store/sessionStore";
import Die from "@/components/dice/Die";
import type { CombatActionEvent, CombatTarget } from "@/types";
import { conditionMeta } from "@/lib/conditions";
import { formatDamageRoll } from "@/lib/combat";
import { bandMeta } from "@/lib/health";
import { playSound, type SoundName } from "@/lib/sound";
import type { PendingCombatAction } from "@/components/game/hooks/useCombatActionGate";

// "rolling" → the d20 tumbles; "revealed" → result held (then the damage gate, for own hits).
type Phase = "rolling" | "revealed";

const ROLL_MS = 650;
// Dwell long enough that each action (the player's, then every enemy in initiative order) is
// readable rather than flashing past — kept calm so a string of enemy turns doesn't overwhelm.
// Click the modal (once revealed) to fast-forward to the next action.
const BASE_LINGER_MS = 3500;
const PER_TARGET_MS = 500;
// Safety net: don't strand the modal "awaiting" if a sent action emits no combat event.
const AWAIT_TIMEOUT_MS = 15000;

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

/** Pick the cue for an action from its targets' outcomes (defeat > heal > crit > hit > miss). */
function actionSound(evt: CombatActionEvent): SoundName {
  const t = evt.targets;
  if (t.some((x) => x.defeated)) return "defeat";
  if (evt.actionKind === "SPELL_HEAL" || t.some((x) => x.heal != null)) return "heal";
  if (t.some((x) => x.attackRoll?.crit)) return "crit";
  if (t.some((x) => x.hit === true || x.saved === false)) return "hit";
  if (t.some((x) => x.hit === false || x.saved === true)) return "miss";
  return "hit";
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
 * Drives the local player's combat action end-to-end AND plays back the server's combat
 * stream one beat at a time:
 *  1. `pending` — the player's action is held (via {@link useCombatActionGate}); a "Roll d20"
 *     / "Cast" / "Use" gate is shown and NOTHING is sent until they click. This is what makes
 *     DM narration begin only after the roll.
 *  2. `awaiting` — the WS send has fired; the d20 tumbles while we wait for the server.
 *  3. playback — the resolved `COMBAT_ACTION` (server-authoritative dice) animates: d20 settles
 *     on hit/miss, then for the player's OWN hit a "Roll damage" gate reveals the damage dice.
 * Enemy / other-player actions auto-play (damage hidden), exactly as before.
 */
export default function CombatActionModal({
  pendingAction,
  onRoll,
  onCancel,
}: {
  pendingAction: PendingCombatAction | null;
  onRoll: () => void;
  onCancel: () => void;
}) {
  const head = useSessionStore((s) => s.combatActionQueue[0] ?? null);
  const dequeue = useSessionStore((s) => s.dequeueCombatAction);
  const pushFeed = useSessionStore((s) => s.pushCombatFeed);
  const myName = useSessionStore(
    (s) => s.combat?.order.find((c) => c.refId === s.myPlayerId)?.name ?? null
  );

  const [evt, setEvt] = useState<(CombatActionEvent & { eventId: string }) | null>(null);
  const [phase, setPhase] = useState<Phase>("rolling");
  const [dieFace, setDieFace] = useState(1);
  // The player rolled their own action and is waiting for the server's resolution.
  const [awaiting, setAwaiting] = useState(false);
  const [awaitAttack, setAwaitAttack] = useState(false);
  // Whether the player has revealed their own damage (the "Roll damage" gate).
  const [damageRevealed, setDamageRevealed] = useState(false);
  const seen = useRef<string | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const intervals = useRef<ReturnType<typeof setInterval>[]>([]);
  const lingerRef = useRef(BASE_LINGER_MS);

  function clearAll() {
    timers.current.forEach(clearTimeout);
    intervals.current.forEach(clearInterval);
    timers.current = [];
    intervals.current = [];
  }

  function scheduleAdvance() {
    timers.current.push(
      setTimeout(() => {
        setEvt(null);
        dequeue();
      }, lingerRef.current)
    );
  }

  // Play an incoming combat action. No pre-roll idle gate here any more — the player's own
  // roll now happens BEFORE the send, so every arriving action auto-advances.
  useEffect(() => {
    if (!head || head.eventId === seen.current) return;
    seen.current = head.eventId;
    clearAll();
    setAwaiting(false);
    setDamageRevealed(false);
    setEvt(head);

    const lead = head.targets[0] ? primaryRoll(head.targets[0]) : null;
    const finalFace = lead?.faces[0] ?? lead?.total ?? 0;
    lingerRef.current =
      BASE_LINGER_MS + PER_TARGET_MS * Math.max(0, head.targets.length - 1);

    const isOwn =
      head.actorKind === "PLAYER" && !!myName && head.actorName === myName;
    // The player rolls their own damage — hold the advance until they do.
    const gateDamage = isOwn && head.targets.some((t) => t.damageRoll != null);

    const reveal = () => {
      pushFeed(head);
      playSound(actionSound(head));
      if (!gateDamage) scheduleAdvance();
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
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [head, myName]);

  // Safety: clear the awaiting state if no own combat event ever arrives.
  useEffect(() => {
    if (!awaiting) return;
    const t = setTimeout(() => {
      clearAll();
      setAwaiting(false);
    }, AWAIT_TIMEOUT_MS);
    return () => clearTimeout(t);
  }, [awaiting]);

  useEffect(() => () => clearAll(), []);

  /** Player committed their action: fire the send. Attack rolls tumble a d20 while the server
   *  resolves; other actions (cast/use) just send and let any resulting beat auto-play, so an
   *  action that emits no combat event doesn't strand the player on a spinner. */
  function handleRoll() {
    const attack = !!pendingAction?.isAttackRoll;
    if (attack) {
      setAwaitAttack(true);
      setDieFace(1);
      setAwaiting(true);
      if (!reducedMotion()) {
        const cyc = setInterval(() => setDieFace(Math.floor(Math.random() * 20) + 1), 70);
        intervals.current.push(cyc);
      }
    }
    onRoll(); // fires the WS send + clears pendingAction
  }

  /** Reveal the player's own damage, then advance. */
  function handleRollDamage() {
    setDamageRevealed(true);
    playSound("hit");
    scheduleAdvance();
  }

  /* ── PENDING gate: nothing has been sent; the player rolls / confirms first ── */
  if (pendingAction && !evt && !awaiting) {
    const rollLabel = pendingAction.isAttackRoll
      ? "Roll d20"
      : pendingAction.kind === "useItem"
        ? "Use"
        : "Cast";
    return (
      <Modal open onClose={() => {}} dismissible={false} hideClose title="Your move" size="sm">
        <div className="flex flex-col items-center gap-3 text-center">
          <p className="font-display text-sm font-bold text-accent">
            {pendingAction.label}
          </p>
          {pendingAction.targetNames.length > 0 && (
            <p className="text-xs text-text-muted">
              {pendingAction.targetNames.join(", ")}
            </p>
          )}
          <Button
            variant="primary"
            size="lg"
            fullWidth
            onClick={handleRoll}
            className="active:scale-95"
            autoFocus
          >
            {rollLabel}
          </Button>
          <button
            type="button"
            onClick={onCancel}
            className="text-[11px] text-text-muted underline-offset-2 transition hover:text-accent hover:underline"
          >
            Cancel
          </button>
        </div>
      </Modal>
    );
  }

  /* ── AWAITING: the send fired — wait for the server's resolution ── */
  if (awaiting && !evt) {
    return (
      <Modal open onClose={() => {}} dismissible={false} hideClose title="Rolling…" size="sm">
        <div className="flex flex-col items-center gap-3 py-2 text-center">
          {awaitAttack ? (
            <Die value={dieFace} sides={20} rolling />
          ) : (
            <Spinner className="h-6 w-6 text-gold" />
          )}
          <p className="text-[10px] uppercase tracking-widest text-text-muted">
            Resolving…
          </p>
        </div>
      </Modal>
    );
  }

  if (!evt) return null;

  const lead = evt.targets[0] ? primaryRoll(evt.targets[0]) : null;
  const isHeal = evt.actionKind === "SPELL_HEAL";
  const isOwnEvent =
    evt.actorKind === "PLAYER" && !!myName && evt.actorName === myName;
  const needsDamageGate =
    isOwnEvent && evt.targets.some((t) => t.damageRoll != null);
  // Hide damage + post-hit HP until the player rolls their own damage.
  const damageHidden = needsDamageGate && !damageRevealed;

  return (
    <Modal
      open={!!evt}
      // Backdrop / ESC fast-forwards to the next action (also reveals damage if still gated).
      onClose={() => {
        setEvt(null);
        dequeue();
      }}
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
                      !damageHidden &&
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
                    {!damageHidden && (
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
                    )}
                  </span>
                </div>
              );
            })}
          </div>
        )}

        {/* The player rolls their own damage (Part C). */}
        {phase === "revealed" && damageHidden && (
          <Button
            variant="primary"
            size="lg"
            fullWidth
            onClick={handleRollDamage}
            className="active:scale-95"
            autoFocus
          >
            Roll damage
          </Button>
        )}

        {phase === "revealed" && !damageHidden && evt.targets.some((t) => t.defeated) && (
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
