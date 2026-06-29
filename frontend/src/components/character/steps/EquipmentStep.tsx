"use client";

import { useEquipmentKindMap } from "@/hooks/useDnd5eData";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import { classEquipOptions, resolvedItems } from "@/lib/characterCreation";
import {
  StepHeading,
  Guide,
  DetailPanel,
  EquipRadio,
} from "@/components/character/shared";

export default function EquipmentStep() {
  const draft = useCharacterDraftStore();
  const {
    selectedClass,
    selectedBackground,
    classEquipLetter,
    bgEquipLetter,
    setClassEquipLetter,
    setBgEquipLetter,
  } = draft;
  const equipKindMapQuery = useEquipmentKindMap();

  const options = classEquipOptions(draft);
  const items = resolvedItems(draft, equipKindMapQuery.data);

  return (
    <div>
      <StepHeading>Starting Equipment</StepHeading>
      <Guide>
        Your class and background each grant a starting equipment bundle. Pick one
        option from each; the selected items become your starting inventory.
      </Guide>

      {selectedClass && (
        <DetailPanel title={`${selectedClass.name} — choose one`}>
          <div className="space-y-2">
            {options.map((opt) => (
              <EquipRadio
                key={opt.letter}
                name="class-equip"
                label={`Option ${opt.letter}`}
                value={opt.letter}
                checked={classEquipLetter === opt.letter}
                onChange={() => setClassEquipLetter(opt.letter)}
                text={opt.raw}
              />
            ))}
          </div>
        </DetailPanel>
      )}

      {selectedBackground && (
        <div className="mt-4">
          <DetailPanel title={`${selectedBackground.name} — choose one`}>
            <div className="space-y-2">
              <EquipRadio
                name="bg-equip-2"
                label="Option A"
                value="A"
                checked={bgEquipLetter === "A"}
                onChange={() => setBgEquipLetter("A")}
                text={selectedBackground.equipment.optionA}
              />
              <EquipRadio
                name="bg-equip-2"
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

      {items.length > 0 && (
        <div className="mt-4">
          <DetailPanel title="Resulting Inventory">
            <div className="flex flex-wrap gap-1">
              {items.map((i) => (
                <span
                  key={`${i.name}-${i.kind}`}
                  className="rounded bg-surface-light px-2 py-0.5 text-xs text-text-muted"
                >
                  {i.qty > 1 ? `${i.name} ×${i.qty}` : i.name}
                </span>
              ))}
            </div>
          </DetailPanel>
        </div>
      )}
    </div>
  );
}
