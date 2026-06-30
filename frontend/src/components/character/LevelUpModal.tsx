"use client";

import { useEffect, useMemo, useState } from "react";
import { Modal, Button, useToast, cn, controlClass } from "@/components/ui";
import { SpellPicker } from "@/components/character/shared";
import { useClasses, useClassSpells } from "@/hooks/useDnd5eData";
import {
  useLevelUpCharacter,
  useApplyLevelChoices,
} from "@/hooks/useCharacterQueries";
import { getErrorMessage } from "@/lib/errors";
import {
  ABILITY_NAMES,
  ABILITY_LABELS,
  getAbilityModifier,
  formatModifier,
  type AbilityName,
} from "@/lib/dnd5e";
import { isCasterClass } from "@/lib/characterCreation";
import {
  proficiencyBonusForLevel,
  isAsiLevel,
  fixedHpForHitDie,
  highestSpellLevel,
  newSpellPicksFor,
  ABILITY_CAP,
} from "@/lib/leveling";
import type { CharacterDto, AsiMode } from "@/types";

/**
 * Guided level-up for a character. In `advance` mode it gains a full level (auto gains +
 * choices) via `/level-up`. In `pending` mode it resolves the ASI / new-spell choices a
 * milestone advance deferred — the mechanical level already applied — via `/level-choices`.
 */
export default function LevelUpModal({
  character,
  open,
  onClose,
  mode = "advance",
}: {
  character: CharacterDto;
  open: boolean;
  onClose: () => void;
  mode?: "advance" | "pending";
}) {
  const toast = useToast();
  const levelUp = useLevelUpCharacter();
  const applyChoices = useApplyLevelChoices();
  const advancing = mode === "advance";
  const mutation = advancing ? levelUp : applyChoices;

  const classesQuery = useClasses();
  const classInfo = useMemo(
    () => classesQuery.data?.find((c) => c.name === character.characterClass) ?? null,
    [classesQuery.data, character.characterClass]
  );

  // Advance: the level we're gaining. Pending: the earliest owed level (already applied).
  const targetLevel = advancing
    ? character.level + 1
    : character.pendingChoiceLevels.length > 0
      ? Math.min(...character.pendingChoiceLevels)
      : character.level;
  const isAsi = isAsiLevel(targetLevel);
  const caster = isCasterClass(classInfo);
  const picks = newSpellPicksFor(classInfo, targetLevel);

  /* ── Choice state ───────────────────────────────────────────── */
  const [asiMode, setAsiMode] = useState<AsiMode>("PLUS_TWO");
  const [first, setFirst] = useState<AbilityName | "">("");
  const [second, setSecond] = useState<AbilityName | "">("");
  const [selectedCantrips, setSelectedCantrips] = useState<string[]>([]);
  const [selectedSpells, setSelectedSpells] = useState<string[]>([]);

  // Reset every time the modal opens for a (possibly different) character.
  useEffect(() => {
    if (!open) return;
    setAsiMode("PLUS_TWO");
    setFirst("");
    setSecond("");
    setSelectedCantrips([]);
    setSelectedSpells([]);
  }, [open, character.id]);

  /* ── Spell choices (exclude already-known) ──────────────────── */
  const spellsQuery = useClassSpells(
    classInfo?.index,
    open && caster && (picks.cantrips > 0 || picks.spells > 0)
  );
  const knownCantrips = new Set(character.cantrips.map((s) => s.toLowerCase()));
  const knownSpells = new Set(character.knownSpells.map((s) => s.toLowerCase()));
  const cantripChoices = (spellsQuery.data?.cantrips ?? []).filter(
    (s) => !knownCantrips.has(s.name.toLowerCase())
  );
  const spellChoices = (spellsQuery.data?.leveled ?? []).filter(
    (s) => s.level <= picks.maxSpellLevel && !knownSpells.has(s.name.toLowerCase())
  );

  /* ── Derived gains (CON ASI feeds this level's HP) ──────────── */
  const asiBonus = (ability: AbilityName): number => {
    if (!isAsi) return 0;
    if (asiMode === "PLUS_TWO") return first === ability ? 2 : 0;
    return (first === ability ? 1 : 0) + (second === ability ? 1 : 0);
  };
  const previewCon = character.constitution + asiBonus("constitution");
  const hitDie = classInfo?.hitDie ?? 8;
  const hpGain = Math.max(1, fixedHpForHitDie(hitDie) + getAbilityModifier(previewCon));

  const profOld = proficiencyBonusForLevel(character.level);
  const profNew = proficiencyBonusForLevel(targetLevel);
  const spellLevelOld = highestSpellLevel(classInfo, character.level);
  const spellLevelNew = highestSpellLevel(classInfo, targetLevel);
  const unlockedSpellLevel = spellLevelNew > spellLevelOld ? spellLevelNew : 0;

  /* ── Validation ─────────────────────────────────────────────── */
  const asiReady =
    !isAsi ||
    (asiMode === "PLUS_TWO" ? !!first : !!first && !!second && first !== second);
  const spellsReady =
    selectedCantrips.length === picks.cantrips &&
    selectedSpells.length === picks.spells;
  const canConfirm = asiReady && spellsReady && !mutation.isPending;

  const toggle = (
    setter: React.Dispatch<React.SetStateAction<string[]>>,
    name: string
  ) =>
    setter((prev) =>
      prev.includes(name) ? prev.filter((n) => n !== name) : [...prev, name]
    );

  async function handleConfirm() {
    try {
      await mutation.mutateAsync({
        id: character.id,
        body: {
          asi: isAsi
            ? {
                mode: asiMode,
                first: first as AbilityName,
                second: asiMode === "TWO_PLUS_ONE" ? (second as AbilityName) : null,
              }
            : null,
          newCantrips: selectedCantrips,
          newSpells: selectedSpells,
        },
      });
      toast.success(
        advancing
          ? `${character.name} is now level ${targetLevel}!`
          : `${character.name}'s level-${targetLevel} choices are set!`
      );
      onClose();
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, advancing ? "Failed to level up" : "Failed to save choices"));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      dismissible={!mutation.isPending}
      title={advancing ? `Level Up — ${character.name}` : `Complete Level ${targetLevel} — ${character.name}`}
    >
      {/* Celebration / context banner */}
      <div className="mb-5 rounded-xl border border-border-accent bg-bg-elevated p-5 text-center panel-corners">
        <p className="text-xs uppercase tracking-[0.2em] text-text-muted">
          {character.race} {character.characterClass}
        </p>
        {advancing ? (
          <p
            className="mt-1 flex items-center justify-center gap-3 text-2xl font-bold text-text"
            style={{ fontFamily: "var(--font-display)" }}
          >
            <span className="tabular text-text-muted">Lv {character.level}</span>
            <span className="text-accent">→</span>
            <span className="tabular text-gold">Lv {targetLevel}</span>
          </p>
        ) : (
          <>
            <p
              className="mt-1 text-2xl font-bold text-gold"
              style={{ fontFamily: "var(--font-display)" }}
            >
              Level {targetLevel}
            </p>
            <p className="mt-1 text-xs text-text-muted">
              A milestone advanced you here — finish your choices below.
            </p>
          </>
        )}
      </div>

      {/* Automatic gains — only when actually advancing a level */}
      {advancing && (
        <div className="mb-5 grid grid-cols-3 gap-3">
          <Gain label="Hit Points" value={`+${hpGain}`} hint={`${character.hitPoints} → ${character.hitPoints + hpGain}`} />
          <Gain
            label="Proficiency"
            value={`+${profNew}`}
            hint={profNew > profOld ? `up from +${profOld}` : "no change"}
            highlight={profNew > profOld}
          />
          <Gain label="Hit Die" value={`+1 d${hitDie}`} hint="added to your pool" />
        </div>
      )}

      {advancing && unlockedSpellLevel > 0 && (
        <div className="mb-5 rounded-lg border border-gold/40 bg-gold/5 px-4 py-3 text-sm text-gold">
          You can now cast <span className="font-semibold">level {unlockedSpellLevel}</span> spells.
          New spell slots apply the next time you enter a session.
        </div>
      )}

      {/* Ability Score Improvement */}
      {isAsi && (
        <section className="mb-5">
          <SectionHeading>Ability Score Improvement</SectionHeading>
          <p className="mb-3 text-xs text-text-muted">
            Raise one ability by +2, or two different abilities by +1 each (max {ABILITY_CAP}).
          </p>

          <div className="mb-3 inline-flex rounded-lg border border-border bg-bg-elevated p-1">
            {(
              [
                ["PLUS_TWO", "+2 to one"],
                ["TWO_PLUS_ONE", "+1 to two"],
              ] as const
            ).map(([mode, label]) => (
              <button
                key={mode}
                type="button"
                aria-pressed={asiMode === mode}
                onClick={() => {
                  setAsiMode(mode);
                  setFirst("");
                  setSecond("");
                }}
                className={cn(
                  "cursor-pointer rounded-md px-3 py-1.5 text-xs font-semibold transition",
                  asiMode === mode
                    ? "bg-accent text-white"
                    : "text-text-muted hover:text-text"
                )}
              >
                {label}
              </button>
            ))}
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <AbilitySelect
              id="asi-first"
              label={asiMode === "PLUS_TWO" ? "+2 to" : "+1 to"}
              value={first}
              onChange={setFirst}
              scores={character}
              bump={asiMode === "PLUS_TWO" ? 2 : 1}
              exclude={second}
            />
            {asiMode === "TWO_PLUS_ONE" && (
              <AbilitySelect
                id="asi-second"
                label="+1 to"
                value={second}
                onChange={setSecond}
                scores={character}
                bump={1}
                exclude={first}
              />
            )}
          </div>
        </section>
      )}

      {/* Spell picks (casters) */}
      {caster && picks.cantrips > 0 && (
        <section className="mb-5">
          <SectionHeading>New Cantrip{picks.cantrips > 1 ? "s" : ""}</SectionHeading>
          <SpellPicker
            title="Cantrips"
            picked={selectedCantrips.length}
            max={picks.cantrips}
            choices={cantripChoices}
            selectedNames={selectedCantrips}
            onToggle={(n) => toggle(setSelectedCantrips, n)}
          />
        </section>
      )}
      {caster && picks.spells > 0 && (
        <section className="mb-5">
          <SectionHeading>New Spell{picks.spells > 1 ? "s" : ""}</SectionHeading>
          <SpellPicker
            title={`Spells (up to level ${picks.maxSpellLevel})`}
            picked={selectedSpells.length}
            max={picks.spells}
            choices={spellChoices}
            selectedNames={selectedSpells}
            onToggle={(n) => toggle(setSelectedSpells, n)}
          />
        </section>
      )}

      {/* Actions */}
      <div className="flex gap-3 border-t border-border pt-4">
        <Button onClick={onClose} variant="ghost" fullWidth disabled={mutation.isPending}>
          Cancel
        </Button>
        <Button onClick={handleConfirm} disabled={!canConfirm} loading={mutation.isPending} fullWidth>
          {advancing ? "Confirm Level Up" : "Save Choices"}
        </Button>
      </div>
    </Modal>
  );
}

/* ── Local presentational helpers ─────────────────────────────── */

function SectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-accent">
      {children}
    </h3>
  );
}

function Gain({
  label,
  value,
  hint,
  highlight = true,
}: {
  label: string;
  value: string;
  hint: string;
  highlight?: boolean;
}) {
  return (
    <div className="rounded-lg border border-border bg-bg-elevated p-3 text-center">
      <div className={cn("tabular text-lg font-bold", highlight ? "text-gold" : "text-text-muted")}>
        {value}
      </div>
      <div className="text-[10px] uppercase tracking-wider text-text-muted">{label}</div>
      <div className="mt-0.5 text-[10px] text-text-muted">{hint}</div>
    </div>
  );
}

function AbilitySelect({
  id,
  label,
  value,
  onChange,
  scores,
  bump,
  exclude,
}: {
  id: string;
  label: string;
  value: AbilityName | "";
  onChange: (v: AbilityName | "") => void;
  scores: CharacterDto;
  bump: number;
  exclude: AbilityName | "";
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-xs font-semibold text-text-muted">
        {label}
      </label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange((e.target.value || "") as AbilityName | "")}
        className={controlClass}
      >
        <option value="">-- choose --</option>
        {ABILITY_NAMES.map((a) => {
          const current = scores[a] as number;
          const tooHigh = current + bump > ABILITY_CAP;
          return (
            <option key={a} value={a} disabled={tooHigh || exclude === a}>
              {ABILITY_LABELS[a]} {current} ({formatModifier(getAbilityModifier(current))})
              {tooHigh ? " — max" : ""}
            </option>
          );
        })}
      </select>
    </div>
  );
}
