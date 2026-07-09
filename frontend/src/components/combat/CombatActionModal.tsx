"use client";

import { useEffect, useRef, useState } from "react";
import { Modal, Button, Spinner, cn } from "@/components/ui";
import { useSessionStore } from "@/store/sessionStore";
import Die from "@/components/dice/Die";
import type { CombatActionEvent, CombatTarget, RollSummary } from "@/types";
import { isBossAction } from "@/types";
import { conditionMeta } from "@/lib/conditions";
import { bandMeta } from "@/lib/health";
import { playSound, type SoundName } from "@/lib/sound";
import type { PendingCombatAction } from "@/components/game/hooks/useCombatActionGate";

// "rolling" → the d20 tumbles; "revealed" → result held (then the damage gate, for own hits).
type Phase = "rolling" | "revealed";
// Player's own damage: "gate" (Roll damage button) → "rolling" (dice tumble) → "shown".
type DmgPhase = "gate" | "rolling" | "shown";

const ROLL_MS = 650;
const DMG_ROLL_MS = 600;
// Dwell long enough that each action (the player's, then every enemy in initiative order) is
// readable rather than flashing past. Click the modal (once revealed) to fast-forward.
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

/** Die size for a damage expression — parsed from the notation ("1d8+3" → 8); no `sides` field. */
function damageSides(notation: string): number {
  const m = /\d+\s*d\s*(\d+)/i.exec(notation);
  return m ? Number(m[1]) : 6;
}

/** Random per-die faces for the damage tumble, keyed by target index. */
function randomDamageFaces(evt: CombatActionEvent): Record<number, number[]> {
  const out: Record<number, number[]> = {};
  evt.targets.forEach((t, i) => {
    if (t.damageRoll) {
      const sides = damageSides(t.damageRoll.notation);
      out[i] = t.damageRoll.faces.map(() => Math.floor(Math.random() * sides) + 1);
    }
  });
  return out;
}

/** Pick the cue for an action from its targets' outcomes (defeat > heal > crit > hit > miss). */
function actionSound(evt: CombatActionEvent): SoundName {
  const t = evt.targets;
  if (t.some((x) => x.defeated)) return "defeat";
  if (evt.actionKind === "SPELL_HEAL" || t.some((x) => x.heal != null)) return "heal";
  if (t.some((x) => x.attackRoll?.crit)) return "crit";
  if (t.some((x) => x.hit === true || x.saved === false)) return "hit";
  if (t.some((x) => x.hit === false || x.saved === true)) return "miss";
  // A boss's flavour beat rolls nothing — cue it as a miss rather than a phantom hit.
  if (isBossAction(evt.actionKind)) return "miss";
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

/** Animated row of damage dice for one target (tumble while `rolling`, then the total). */
function DamageDice({
  roll,
  faces,
  rolling,
}: {
  roll: RollSummary;
  faces: number[];
  rolling: boolean;
}) {
  const sides = damageSides(roll.notation);
  const mod = roll.total - roll.faces.reduce((a, b) => a + b, 0);
  return (
    <span className="flex flex-wrap items-center justify-center gap-1">
      {faces.map((f, i) => (
        <Die
          key={i}
          value={f}
          sides={sides}
          rolling={rolling}
          crit={!rolling && f === sides}
          size={26}
        />
      ))}
      {mod !== 0 && (
        <span className="font-mono text-[11px] text-text-muted">
          {mod > 0 ? `+${mod}` : mod}
        </span>
      )}
      {!rolling && (
        <span className="font-display text-base font-bold text-danger">
          = {roll.total}
        </span>
      )}
    </span>
  );
}

/**
 * Drives the local player's combat action end-to-end AND plays back the server's combat
 * stream one beat at a time:
 *  1. `pending` — the action is held (via {@link useCombatActionGate}); a "Roll d20"/"Cast"/
 *     "Use" gate shows and NOTHING is sent until they click.
 *  2. `awaiting` — the send fired; the d20 tumbles while we wait for the server.
 *  3. playback — the resolved `COMBAT_ACTION` animates: d20 settles on hit/miss, then for the
 *     player's OWN hit a "Roll damage" gate rolls the damage dice. For a weapon attack that's
 *     a true second server phase (`awaitingDamage` → `combatAttackDamage`); for spells the
 *     damage is already resolved and just animates locally.
 * Enemy / other-player actions auto-play (damage hidden).
 */
export default function CombatActionModal({
  pendingAction,
  onRoll,
  onCancel,
  onRollDamage,
}: {
  pendingAction: PendingCombatAction | null;
  onRoll: () => void;
  onCancel: () => void;
  /** Phase-2 send for a weapon hit: asks the server to roll the authoritative damage. */
  onRollDamage: () => void;
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
  const [awaiting, setAwaiting] = useState(false);
  const [awaitAttack, setAwaitAttack] = useState(false);
  const [dmgPhase, setDmgPhase] = useState<DmgPhase>("shown");
  const [dmgFaces, setDmgFaces] = useState<Record<number, number[]>>({});
  const seen = useRef<string | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const intervals = useRef<ReturnType<typeof setInterval>[]>([]);
  const lingerRef = useRef(BASE_LINGER_MS);
  // Set when we sent a phase-2 damage request — the next own damage event auto-rolls.
  const pendingDamage = useRef(false);

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

  /** Tumble the damage dice, then settle on the server's authoritative faces and advance. */
  function startDamageRoll(ev: CombatActionEvent) {
    playSound("dice");
    if (reducedMotion()) {
      setDmgPhase("shown");
      playSound("hit");
      scheduleAdvance();
      return;
    }
    setDmgPhase("rolling");
    const cyc = setInterval(() => setDmgFaces(randomDamageFaces(ev)), 70);
    intervals.current.push(cyc);
    timers.current.push(
      setTimeout(() => {
        clearInterval(cyc);
        setDmgPhase("shown");
        playSound("hit");
        scheduleAdvance();
      }, DMG_ROLL_MS)
    );
  }

  // Play an incoming combat action. No pre-roll idle gate here — the player's own attack roll
  // happens BEFORE the send, so every arriving action auto-advances (or waits on the damage gate).
  useEffect(() => {
    if (!head || head.eventId === seen.current) return;
    seen.current = head.eventId;
    clearAll();
    setAwaiting(false);
    setDmgFaces({});
    setEvt(head);

    const lead = head.targets[0] ? primaryRoll(head.targets[0]) : null;
    const finalFace = lead?.faces[0] ?? lead?.total ?? 0;
    lingerRef.current =
      BASE_LINGER_MS + PER_TARGET_MS * Math.max(0, head.targets.length - 1);

    const isOwn =
      head.actorKind === "PLAYER" && !!myName && head.actorName === myName;
    const hasDamage = head.targets.some((t) => t.damageRoll != null);
    // Own hits gate behind a "Roll damage" click: a weapon phase-1 hit (awaitingDamage) or any
    // own action that dealt damage (spell / auto-hit).
    const gateDamage = isOwn && (hasDamage || head.awaitingDamage === true);
    // The phase-2 weapon damage event (sent by us a moment ago) auto-rolls instead of gating.
    const auto = pendingDamage.current && isOwn && hasDamage;
    pendingDamage.current = false;
    setDmgPhase(auto ? "rolling" : gateDamage ? "gate" : "shown");

    const reveal = () => {
      pushFeed(head);
      playSound(actionSound(head));
      if (!gateDamage && !auto) scheduleAdvance();
    };

    const finishReveal = () => {
      setPhase("revealed");
      reveal();
      if (auto) startDamageRoll(head);
    };

    const run = () => {
      if (reducedMotion() || !lead) {
        setDieFace(finalFace || 1);
        finishReveal();
        return;
      }
      setPhase("rolling");
      const cyc = setInterval(() => setDieFace(Math.floor(Math.random() * 20) + 1), 70);
      intervals.current.push(cyc);
      timers.current.push(
        setTimeout(() => {
          clearInterval(cyc);
          setDieFace(finalFace || 1);
          finishReveal();
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
   *  resolves; other actions (cast/use) just send and let any resulting beat auto-play. */
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

  /** "Roll damage": for a weapon hit this fires phase 2 (server rolls authoritative damage,
   *  which returns as a new event that auto-animates); for spells the damage is already
   *  resolved, so animate it locally. */
  function handleRollDamage() {
    if (!evt) return;
    if (evt.awaitingDamage) {
      onRollDamage();
      pendingDamage.current = true;
      clearAll();
      setEvt(null);
      dequeue();
    } else {
      startDamageRoll(evt);
    }
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
  const hasDamage = evt.targets.some((t) => t.damageRoll != null);
  const showDamageGate =
    isOwnEvent && dmgPhase === "gate" && (hasDamage || evt.awaitingDamage === true);
  // Hide damage + post-hit HP until the player rolls their own damage.
  const damageHidden = isOwnEvent && dmgPhase === "gate";

  return (
    <Modal
      open={!!evt}
      // Backdrop / ESC fast-forwards to the next action.
      onClose={() => {
        setEvt(null);
        dequeue();
      }}
      // Lock the modal while the player's own damage is still gated (must roll it).
      dismissible={!showDamageGate}
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
              const showDmg =
                t.damageRoll && (dmgPhase === "rolling" || dmgPhase === "shown");
              return (
                <div
                  key={`${t.targetName}-${i}`}
                  className="flex flex-col gap-1 rounded-md border border-border bg-surface px-2.5 py-1.5 text-xs"
                >
                  <div className="flex items-center justify-between gap-2">
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

                  {/* Damage: enemy shows only a total; the player's own damage rolls dice. */}
                  {showDmg &&
                    (evt.actorKind === "ENEMY" ? (
                      <span className="self-end font-display text-sm font-bold text-danger">
                        {t.damageRoll!.total}
                      </span>
                    ) : (
                      <div className="self-end">
                        <DamageDice
                          roll={t.damageRoll!}
                          faces={
                            dmgPhase === "rolling"
                              ? dmgFaces[i] ?? t.damageRoll!.faces
                              : t.damageRoll!.faces
                          }
                          rolling={dmgPhase === "rolling"}
                        />
                      </div>
                    ))}
                </div>
              );
            })}
          </div>
        )}

        {/* The player rolls their own damage (Parts B/C). */}
        {phase === "revealed" && showDamageGate && (
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
