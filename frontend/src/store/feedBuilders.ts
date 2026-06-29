import type { CombatActionEvent, DiceRollEvent } from "@/types";
import { conditionMeta } from "@/lib/conditions";
import { bandMeta } from "@/lib/health";
import { formatDamageRoll } from "@/lib/combat";

/* ─── Compact combat roll feed (docked on the map; non-blocking) ─── */
export interface FeedRoll {
  sides: number;
  faces: number[];
  total: number;
  crit: boolean;
  fumble: boolean;
}

/** One line in the map-docked roll feed (enemy attacks, NPC/other-player rolls). */
export interface FeedEntry {
  id: string;
  actorName: string;
  actorKind?: "PLAYER" | "ENEMY";
  title: string;
  roll: FeedRoll | null;
  outcome: string | null;
  detail: string | null;
  tone: "good" | "bad" | "neutral";
}

/** Most recent entries kept in the feed (older ones scroll out; chat log keeps the full record). */
export const FEED_CAP = 8;

/** Prepend an action's entries (newest first) and cap the list. */
export function prependFeed(feed: FeedEntry[], entries: FeedEntry[]): FeedEntry[] {
  if (entries.length === 0) return feed;
  return [...entries, ...feed].slice(0, FEED_CAP);
}

/** A single d20 roll line from a DiceRollEvent. */
export function feedFromRoll(evt: DiceRollEvent): FeedEntry {
  return {
    id: `rf-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    actorName: evt.playerName,
    title: evt.label,
    roll: {
      sides: evt.sides,
      faces: evt.faces,
      total: evt.total,
      crit: evt.crit,
      fumble: evt.fumble,
    },
    outcome: evt.crit ? "Crit!" : evt.fumble ? "Fumble!" : null,
    detail: evt.notation,
    tone: evt.crit ? "good" : evt.fumble ? "bad" : "neutral",
  };
}

/** One feed line per target of a combat action. Pure-move actions (no targets) yield nothing. */
export function feedFromAction(evt: CombatActionEvent): FeedEntry[] {
  const base = `caf-${evt.seq}-${Date.now()}-${Math.random()
    .toString(36)
    .slice(2, 6)}`;
  return evt.targets.map((t, i) => {
    const r = t.attackRoll ?? t.saveRoll;
    const roll: FeedRoll | null = r
      ? { sides: 20, faces: r.faces, total: r.total, crit: r.crit, fumble: r.fumble }
      : null;

    // Outcome + whether it benefits the party (drives colour from the players' POV).
    let outcome: string | null = null;
    let good: boolean | null = null;
    if (t.hit !== null) {
      outcome = t.hit ? "Hit" : "Miss";
      good = t.targetKind === "ENEMY" ? t.hit : !t.hit;
    } else if (t.saved !== null) {
      outcome = t.saved ? "Saved" : "Failed";
      good = t.targetKind === "ENEMY" ? !t.saved : t.saved;
    } else if (t.heal !== null && t.heal > 0) {
      outcome = "Heal";
      good = true;
    } else if (t.condition) {
      outcome = conditionMeta(t.condition).label;
      good = t.targetKind === "ENEMY";
    }
    if (t.defeated) {
      outcome = "Down!";
      good = t.targetKind === "ENEMY";
    }

    const bits: string[] = [];
    if (t.damageRoll) {
      // Hide the enemy's damage DICE — show only the total they dealt; the player's own
      // damage shows the full notation + per-die breakdown.
      bits.push(
        evt.actorKind === "ENEMY"
          ? `${t.damageRoll.total} dmg`
          : `${t.damageRoll.notation} ${formatDamageRoll(t.damageRoll)} dmg`
      );
    }
    if (t.heal !== null && t.heal > 0) bits.push(`+${t.heal}`);
    // Enemy HP is hidden — show the band; players show exact HP.
    bits.push(t.healthBand ? bandMeta(t.healthBand).label : `${Math.max(0, t.currentHp)}/${t.maxHp}`);

    const tone: FeedEntry["tone"] =
      good === true ? "good" : good === false ? "bad" : "neutral";

    return {
      id: `${base}-${i}`,
      actorName: evt.actorName,
      actorKind: evt.actorKind,
      title: `${evt.label} ${t.targetName}`.trim(),
      roll,
      outcome,
      detail: bits.join(" · "),
      tone,
    };
  });
}
