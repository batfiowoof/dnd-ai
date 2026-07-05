"use client";

import type { PlayerRuntimeState } from "@/types";
import { Modal, cn } from "@/components/ui";
import { formatCoins } from "@/lib/money";
import { SLOT_LABELS } from "@/lib/itemKinds";
import Portrait from "@/components/Portrait";
import CharacterStatus from "@/components/game/CharacterStatus";
import SrdEntryRow from "@/components/game/SrdEntryRow";
import {
  getAbilityModifier,
  formatModifier,
  ABILITY_ABBREVIATIONS,
  SKILL_ABILITIES,
  proficiencyBonusForLevel,
  proficiencyLevelBonus,
  type ProficiencyLevel,
} from "@/lib/dnd5e";

interface CharacterSheetDialogProps {
  open: boolean;
  onClose: () => void;
  state: PlayerRuntimeState;
  characterName?: string;
  imageUrl?: string | null;
}

/**
 * Full character sheet for any player, opened by clicking their side-menu avatar.
 * Shows ability scores + AC (only available here, not in the compact panel), then
 * reuses {@link CharacterStatus} for HP, spell slots, inventory, spells, conditions.
 */
export default function CharacterSheetDialog({
  open,
  onClose,
  state,
  characterName,
  imageUrl,
}: CharacterSheetDialogProps) {
  const abilities = state.abilities ?? {};
  const cantrips = state.cantrips ?? [];
  const knownSpells = state.knownSpells ?? [];
  const hasSpells = cantrips.length > 0 || knownSpells.length > 0;

  // Proficiency bonus scales with character level; the runtime state carries level as its Hit Dice total.
  const proficiencyBonus = proficiencyBonusForLevel(state.hitDiceTotal ?? 1);
  const saveProfs = state.savingThrowProficiencies ?? [];
  const skillProfs = state.skillProficiencies ?? {};

  const saveModifier = (abbr: string): { mod: number; proficient: boolean } => {
    const score = abilities[abbr];
    const base = typeof score === "number" ? getAbilityModifier(score) : 0;
    const proficient = saveProfs.some((a) => a.toUpperCase() === abbr);
    return { mod: base + (proficient ? proficiencyBonus : 0), proficient };
  };

  // The three "passive" skills a DM checks silently; passive score = 10 + the skill check modifier.
  const passiveSkills: (keyof typeof SKILL_ABILITIES)[] = [
    "Perception",
    "Investigation",
    "Insight",
  ];
  const passiveScore = (skill: keyof typeof SKILL_ABILITIES): number => {
    const score = abilities[SKILL_ABILITIES[skill]];
    const base = typeof score === "number" ? getAbilityModifier(score) : 0;
    const level: ProficiencyLevel = skillProfs[skill] ?? "NONE";
    return 10 + base + proficiencyLevelBonus(level, proficiencyBonus);
  };

  return (
    <Modal open={open} onClose={onClose} size="lg">
      <div className="space-y-4">
        {/* Header */}
        <div className="flex items-center gap-3">
          <Portrait src={imageUrl} name={characterName} size="md" />
          <div className="min-w-0">
            <h2 className="truncate font-display text-lg font-bold text-text">
              {characterName ?? "Character"}
            </h2>
            <p className="text-xs text-text-muted">Character sheet</p>
          </div>
          <div className="ml-auto flex flex-col items-center rounded-lg border border-border-accent bg-bg-elevated px-3 py-1.5">
            <span className="text-[10px] uppercase tracking-wider text-text-muted">
              AC
            </span>
            <span className="tabular text-xl font-bold text-text">
              {state.armorClass}
            </span>
          </div>
        </div>

        {/* Ability scores */}
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
          {ABILITY_ABBREVIATIONS.map((abbr) => {
            const score = abilities[abbr];
            const mod = typeof score === "number" ? getAbilityModifier(score) : null;
            return (
              <div
                key={abbr}
                className="flex flex-col items-center rounded-lg border border-border bg-bg-elevated py-2"
              >
                <span className="text-[10px] uppercase tracking-wider text-gold">
                  {abbr}
                </span>
                <span className="tabular text-base font-bold text-text">
                  {score ?? "—"}
                </span>
                <span
                  className={cn(
                    "tabular text-[11px]",
                    mod !== null && mod < 0 ? "text-danger" : "text-text-muted"
                  )}
                >
                  {mod !== null ? formatModifier(mod) : ""}
                </span>
              </div>
            );
          })}
        </div>

        {/* Saving throws — a proficiency dot (+ label) marks class-proficient saves. */}
        <section>
          <SectionHeading>Saving Throws</SectionHeading>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
            {ABILITY_ABBREVIATIONS.map((abbr) => {
              const { mod, proficient } = saveModifier(abbr);
              return (
                <div
                  key={abbr}
                  className={cn(
                    "flex flex-col items-center rounded-lg border py-2",
                    proficient
                      ? "border-border-accent bg-accent-dark/20"
                      : "border-border bg-bg-elevated"
                  )}
                >
                  <span className="flex items-center gap-1 text-[10px] uppercase tracking-wider text-gold">
                    {proficient && (
                      <span
                        className="text-accent-light"
                        aria-label="Proficient"
                        title="Proficient"
                      >
                        ●
                      </span>
                    )}
                    {abbr}
                  </span>
                  <span
                    className={cn(
                      "tabular text-base font-bold",
                      mod < 0 ? "text-danger" : "text-text"
                    )}
                  >
                    {formatModifier(mod)}
                  </span>
                </div>
              );
            })}
          </div>
        </section>

        {/* Passive scores — the skills a DM checks silently (10 + skill modifier). */}
        <section>
          <SectionHeading>Passive Scores</SectionHeading>
          <div className="grid grid-cols-3 gap-2">
            {passiveSkills.map((skill) => (
              <div
                key={skill}
                className="flex flex-col items-center rounded-lg border border-border bg-bg-elevated py-2"
              >
                <span className="text-[10px] uppercase tracking-wider text-text-muted">
                  {skill}
                </span>
                <span className="tabular text-base font-bold text-text">
                  {passiveScore(skill)}
                </span>
              </div>
            ))}
          </div>
        </section>

        {/* HP / spell slots / conditions (compact, non-redundant) */}
        <CharacterStatus state={state} />

        {/* Spells — expand a row for its full SRD description */}
        {hasSpells && (
          <section>
            <SectionHeading>Spells</SectionHeading>
            <div className="space-y-1.5">
              {cantrips.map((name) => (
                <SrdEntryRow key={`c-${name}`} name={name} kind="spell" />
              ))}
              {knownSpells.map((name) => (
                <SrdEntryRow key={`s-${name}`} name={name} kind="spell" />
              ))}
            </div>
          </section>
        )}

        {/* Purse */}
        <section>
          <SectionHeading>Purse</SectionHeading>
          <p className="text-sm font-semibold tabular-nums text-gold">
            {formatCoins(state.copper)}
          </p>
        </section>

        {/* Equipment — expand a row for damage / AC / properties / rules text */}
        {state.inventory.length > 0 && (
          <section>
            <SectionHeading>Equipment</SectionHeading>
            <div className="space-y-1.5">
              {state.inventory.map((item) => (
                <SrdEntryRow
                  key={`${item.name}-${item.kind}`}
                  name={item.name}
                  kind="equipment"
                  accent={item.kind === "POTION_HEALING"}
                  meta={
                    <span className="flex items-center gap-1.5">
                      {(item.slot || item.equipped) && (
                        <span className="text-[9px] uppercase tracking-wider text-accent-light">
                          ◆ {item.slot ? SLOT_LABELS[item.slot] : "eq"}
                        </span>
                      )}
                      {item.qty > 1 && (
                        <span className="tabular text-text">×{item.qty}</span>
                      )}
                    </span>
                  }
                />
              ))}
            </div>
          </section>
        )}
      </div>
    </Modal>
  );
}

function SectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-text-muted">
      {children}
    </h3>
  );
}
