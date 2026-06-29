"use client";

import { useEquipmentKindMap } from "@/hooks/useDnd5eData";
import {
  ABILITY_NAMES,
  getAbilityModifier,
  formatModifier,
} from "@/lib/dnd5e";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import {
  ABILITY_LABELS,
  isCasterClass,
  bgBonuses,
  finalAbilities,
  derivedHP,
  derivedAC,
  derivedSpeed,
  resolvedItems,
} from "@/lib/characterCreation";
import {
  StepHeading,
  DetailPanel,
  ReviewRow,
  Stat,
  ChipRow,
} from "@/components/character/shared";

export default function ReviewStep() {
  const draft = useCharacterDraftStore();
  const {
    name,
    selectedSpecies,
    selectedClass,
    selectedBackground,
    alignment,
    backstory,
    selectedCantrips,
    selectedSpells,
  } = draft;
  const equipKindMapQuery = useEquipmentKindMap();

  const caster = isCasterClass(selectedClass);
  const bonuses = bgBonuses(draft);
  const final = finalAbilities(draft);
  const hp = derivedHP(draft);
  const ac = derivedAC(draft);
  const speed = derivedSpeed(draft);
  const items = resolvedItems(draft, equipKindMapQuery.data);

  return (
    <div>
      <StepHeading>Review Your Character</StepHeading>

      <div className="grid gap-4 sm:grid-cols-2">
        <DetailPanel title="Identity">
          <ReviewRow label="Name" value={name || "Unnamed"} strong />
          <ReviewRow label="Species" value={selectedSpecies?.name ?? "—"} />
          <ReviewRow label="Class" value={selectedClass?.name ?? "—"} />
          <ReviewRow
            label="Background"
            value={selectedBackground?.name ?? "—"}
          />
          <ReviewRow
            label="Origin Feat"
            value={selectedBackground?.feat ?? "—"}
          />
          <ReviewRow label="Alignment" value={alignment || "—"} />
        </DetailPanel>

        <DetailPanel title="Combat">
          <div className="flex justify-around text-center">
            <Stat value={hp} label="HP" />
            <Stat value={ac} label="AC" />
            <Stat value={speed} label="Speed" />
            <Stat value={`d${selectedClass?.hitDie ?? "?"}`} label="Hit Die" />
          </div>
        </DetailPanel>

        <div className="sm:col-span-2">
          <DetailPanel title="Ability Scores">
            <div className="grid grid-cols-6 gap-2 text-center">
              {ABILITY_NAMES.map((a) => {
                const score = final[a];
                const mod = getAbilityModifier(score);
                const bonus = bonuses[a];
                return (
                  <div key={a} className="rounded border border-border p-2">
                    <div className="text-xs font-bold text-accent">
                      {ABILITY_LABELS[a]}
                    </div>
                    <div className="text-lg font-bold text-text tabular">
                      {score}
                    </div>
                    <div className="text-xs text-text-muted">
                      {formatModifier(mod)}
                    </div>
                    {bonus > 0 && (
                      <div className="text-[10px] font-semibold text-gold">
                        +{bonus}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </DetailPanel>
        </div>

        {items.length > 0 && (
          <DetailPanel title="Equipment">
            <ChipRow
              items={items.map((i) =>
                i.qty > 1 ? `${i.name} ×${i.qty}` : i.name
              )}
            />
          </DetailPanel>
        )}

        {caster &&
          (selectedCantrips.length > 0 || selectedSpells.length > 0) && (
            <DetailPanel title="Spells">
              {selectedCantrips.length > 0 && (
                <div className="mb-2">
                  <div className="mb-1 text-[10px] uppercase tracking-wider text-gold">
                    Cantrips
                  </div>
                  <ChipRow items={selectedCantrips} />
                </div>
              )}
              {selectedSpells.length > 0 && (
                <div>
                  <div className="mb-1 text-[10px] uppercase tracking-wider text-gold">
                    Level 1
                  </div>
                  <ChipRow items={selectedSpells} />
                </div>
              )}
            </DetailPanel>
          )}

        {selectedSpecies && selectedSpecies.traits.length > 0 && (
          <DetailPanel title="Species Traits">
            <ChipRow items={selectedSpecies.traits.map((t) => t.name)} />
          </DetailPanel>
        )}

        {backstory && (
          <div className="sm:col-span-2">
            <DetailPanel title="Backstory">
              <p className="whitespace-pre-wrap text-sm text-text-muted">
                {backstory}
              </p>
            </DetailPanel>
          </div>
        )}
      </div>
    </div>
  );
}
