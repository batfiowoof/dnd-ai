"use client";

import type { CustomMonster, MonsterAttack } from "@/types";
import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateMonster } from "@/hooks/useWorldQueries";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import {
  LabeledInput,
  LabeledNumber,
  LabeledSelect,
} from "@/components/world/shared/WorldField";
import {
  ABILITY_KEYS,
  DEFAULT_ABILITIES,
  abilityMod,
  draftToContext,
  emptyAttack,
  emptyMonster,
} from "@/lib/worldBuilder";

const SIZES = ["Tiny", "Small", "Medium", "Large", "Huge", "Gargantuan"] as const;

/** Step 5 — homebrew monsters as full stat blocks, playable in tactical combat. */
export default function MonstersStep() {
  const draft = useWorldDraftStore();
  const { customMonsters, setField } = draft;
  const toast = useToast();
  const generate = useGenerateMonster();

  async function handleGenerate() {
    try {
      const m = await generate.mutateAsync(draftToContext(draft));
      // Backfill anything the model omitted so the form renders cleanly.
      const monster: CustomMonster = {
        ...m,
        abilities: { ...DEFAULT_ABILITIES, ...(m.abilities ?? {}) },
        attacks: m.attacks ?? [],
      };
      setField({ customMonsters: [...customMonsters, monster] });
      toast.success(`Added ${m.name}`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate monster"));
    }
  }

  return (
    <div>
      <SectionIntro title="Monsters">
        These are real combatants — the DM can run them in tactical combat. Each needs an{" "}
        <strong className="text-text">AC</strong>, <strong className="text-text">HP</strong>, and at
        least one <strong className="text-text">attack</strong> to be playable. Abilities drive saving
        throws.
      </SectionIntro>

      <RepeatableSection<CustomMonster>
        noun="monsters"
        items={customMonsters}
        onChange={(customMonsters) => setField({ customMonsters })}
        makeEmpty={emptyMonster}
        aiSlot={
          <AiGenerateButton
            onClick={handleGenerate}
            loading={generate.isPending}
            label="Generate monster"
          />
        }
        titleOf={(m) => m.name}
        renderItem={(monster, update) => (
          <MonsterForm monster={monster} update={update} />
        )}
      />
    </div>
  );
}

function MonsterForm({
  monster,
  update,
}: {
  monster: CustomMonster;
  update: (patch: Partial<CustomMonster>) => void;
}) {
  function setAttacks(attacks: MonsterAttack[]) {
    // Keep multiattack pointed at the first attack so the engine knows which to repeat.
    const multiattack =
      monster.multiattack && monster.multiattack.count > 1
        ? { count: monster.multiattack.count, attack: attacks[0]?.name ?? null }
        : monster.multiattack;
    update({ attacks, multiattack });
  }

  function setPerTurn(count: number | null) {
    const c = count ?? 1;
    update({
      multiattack: c > 1 ? { count: c, attack: monster.attacks[0]?.name ?? null } : null,
    });
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <LabeledInput
          label="Name"
          placeholder="e.g. Ash Wraith"
          value={monster.name}
          onChange={(v) => update({ name: v })}
        />
        <LabeledInput
          label="Creature type"
          placeholder="e.g. Undead, Beast"
          value={monster.type ?? ""}
          onChange={(v) => update({ type: v })}
        />
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <LabeledSelect
          label="Size"
          value={monster.size ?? "Medium"}
          onChange={(v) => update({ size: v })}
          options={SIZES}
        />
        <LabeledNumber
          label="CR"
          value={monster.cr}
          onChange={(v) => update({ cr: v })}
          min={0}
          step={0.125}
        />
        <LabeledNumber
          label="AC"
          required
          value={monster.ac}
          onChange={(v) => update({ ac: v })}
          min={1}
        />
        <LabeledNumber
          label="HP"
          required
          value={monster.hp}
          onChange={(v) => update({ hp: v })}
          min={1}
        />
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <LabeledInput
          label="HP dice"
          hint="optional, e.g. 2d8+2"
          value={monster.hpDice ?? ""}
          onChange={(v) => update({ hpDice: v })}
        />
        <LabeledNumber
          label="Speed (ft)"
          value={monster.speed}
          onChange={(v) => update({ speed: v })}
          min={0}
          step={5}
        />
        <LabeledNumber
          label="Attacks / turn"
          hint="multiattack"
          value={monster.multiattack?.count ?? 1}
          onChange={setPerTurn}
          min={1}
          max={4}
        />
      </div>

      {/* Ability scores */}
      <div>
        <label className="mb-1.5 block text-xs font-semibold uppercase tracking-wider text-text-muted">
          Ability scores
        </label>
        <div className="grid grid-cols-6 gap-2">
          {ABILITY_KEYS.map((ability) => {
            const score = monster.abilities?.[ability] ?? 10;
            return (
              <div key={ability} className="text-center">
                <div className="mb-1 text-[10px] font-bold text-accent">{ability}</div>
                <input
                  type="number"
                  value={score}
                  min={1}
                  max={30}
                  onChange={(e) =>
                    update({
                      abilities: {
                        ...monster.abilities,
                        [ability]: Number(e.target.value) || 0,
                      },
                    })
                  }
                  className="w-full rounded-md border border-border bg-bg-elevated px-1 py-1.5 text-center text-sm text-text outline-none transition focus:border-accent"
                />
                <div className="mt-0.5 text-[10px] tabular text-text-muted">
                  {formatMod(abilityMod(score))}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Attacks */}
      <div>
        <label className="mb-2 block text-xs font-semibold uppercase tracking-wider text-text-muted">
          Attacks
        </label>
        <RepeatableSection<MonsterAttack>
          noun="attacks"
          items={monster.attacks}
          onChange={setAttacks}
          makeEmpty={emptyAttack}
          titleOf={(a) => a.name}
          renderItem={(attack, updateAttack) => (
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <LabeledInput
                  label="Name"
                  placeholder="e.g. Spectral Claw"
                  value={attack.name}
                  onChange={(v) => updateAttack({ name: v })}
                />
                <LabeledSelect
                  label="Kind"
                  value={attack.kind}
                  onChange={(v) => updateAttack({ kind: v })}
                  options={["MELEE", "RANGED"]}
                />
              </div>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <LabeledNumber
                  label="To hit"
                  value={attack.toHit}
                  onChange={(v) => updateAttack({ toHit: v ?? 0 })}
                />
                <LabeledInput
                  label="Damage"
                  placeholder="e.g. 2d6+2"
                  value={attack.damageDice}
                  onChange={(v) => updateAttack({ damageDice: v })}
                />
                <LabeledInput
                  label="Damage type"
                  placeholder="e.g. Necrotic"
                  value={attack.damageType}
                  onChange={(v) => updateAttack({ damageType: v })}
                />
                {attack.kind === "RANGED" ? (
                  <LabeledNumber
                    label="Range (ft)"
                    value={attack.range}
                    onChange={(v) => updateAttack({ range: v })}
                    min={0}
                    step={5}
                  />
                ) : (
                  <LabeledNumber
                    label="Reach (ft)"
                    value={attack.reach}
                    onChange={(v) => updateAttack({ reach: v })}
                    min={0}
                    step={5}
                  />
                )}
              </div>
            </div>
          )}
        />
      </div>
    </div>
  );
}

function formatMod(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}
