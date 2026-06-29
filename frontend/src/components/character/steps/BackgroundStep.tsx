"use client";

import { useBackgrounds, useFeat } from "@/hooks/useDnd5eData";
import { featIndexFromName } from "@/lib/dnd5eapi";
import { DataGate } from "@/components/ui";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import {
  StepHeading,
  Guide,
  OptionCard,
  DetailPanel,
  ChipRow,
  EquipRadio,
  FeatCallout,
  FeatIcon,
} from "@/components/character/shared";

export default function BackgroundStep() {
  const backgroundsQuery = useBackgrounds();
  const selectedBackground = useCharacterDraftStore((s) => s.selectedBackground);
  const bgEquipLetter = useCharacterDraftStore((s) => s.bgEquipLetter);
  const setSelectedBackground = useCharacterDraftStore(
    (s) => s.setSelectedBackground
  );
  const setBgEquipLetter = useCharacterDraftStore((s) => s.setBgEquipLetter);

  const featIndex = selectedBackground
    ? featIndexFromName(selectedBackground.feat)
    : undefined;
  const featQuery = useFeat(featIndex, !!featIndex);

  return (
    <div>
      <StepHeading>Choose Your Background</StepHeading>
      <Guide>
        In the 2024 rules your background is mechanical: it grants an
        ability-score increase (assigned in the Abilities step), an{" "}
        <strong className="text-text">origin feat</strong>, skill and tool
        proficiencies, and starting equipment.
      </Guide>

      <DataGate
        query={backgroundsQuery}
        loadingLabel="Loading backgrounds…"
        onRetry={() => backgroundsQuery.refetch()}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          {backgroundsQuery.data?.map((bg) => {
            const selected = selectedBackground?.index === bg.index;
            return (
              <OptionCard
                key={bg.index}
                selected={selected}
                onClick={() => setSelectedBackground(bg)}
                title={bg.name}
              >
                <div className="text-xs text-accent">
                  {bg.abilityScores
                    .map((a) => a.slice(0, 3).toUpperCase())
                    .join(" / ")}{" "}
                  increase
                </div>
                <div className="mt-1 flex items-center gap-1.5 text-xs text-text-muted">
                  <FeatIcon className="h-3.5 w-3.5 text-gold" />
                  {bg.feat}
                </div>
              </OptionCard>
            );
          })}
        </div>

        {selectedBackground && (
          <div className="mt-5 space-y-4">
            {/* Origin feat callout */}
            <FeatCallout
              featName={selectedBackground.feat}
              desc={featQuery.data?.desc}
              loading={featQuery.isLoading}
            />

            <div className="grid gap-4 sm:grid-cols-2">
              <DetailPanel title="Proficiencies">
                <div className="text-xs text-text-muted">Skills</div>
                <ChipRow items={selectedBackground.skillProficiencies} />
                <div className="mt-2 text-xs text-text-muted">Tool</div>
                <ChipRow items={[selectedBackground.toolProficiency]} />
              </DetailPanel>

              <DetailPanel title="Ability Increase">
                <p className="text-xs text-text-muted">
                  Boosts one of these three abilities (you choose how in the
                  Abilities step):
                </p>
                <ChipRow items={selectedBackground.abilityScores} accent />
              </DetailPanel>
            </div>

            {/* Equipment A/B choice */}
            <DetailPanel title="Starting Equipment">
              <div className="space-y-2">
                <EquipRadio
                  name="bg-equip"
                  label="Option A"
                  value="A"
                  checked={bgEquipLetter === "A"}
                  onChange={() => setBgEquipLetter("A")}
                  text={selectedBackground.equipment.optionA}
                />
                <EquipRadio
                  name="bg-equip"
                  label="Option B"
                  value="B"
                  checked={bgEquipLetter === "B"}
                  onChange={() => setBgEquipLetter("B")}
                  text={selectedBackground.equipment.optionB}
                />
              </div>
            </DetailPanel>
          </div>
        )}
      </DataGate>
    </div>
  );
}
