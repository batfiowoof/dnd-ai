"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateNpcs } from "@/hooks/useWorldQueries";
import { draftToContext } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";
import { emptyNpc } from "@/lib/worldBuilder";

/** Step 4 — key NPCs, each tied to a place and ideally bound to the party. */
export default function NpcsStep() {
  const draft = useWorldDraftStore();
  const { npcs, setField } = draft;
  const toast = useToast();
  const generate = useGenerateNpcs();

  async function handleGenerate() {
    try {
      const items = await generate.mutateAsync(draftToContext(draft));
      setField({ npcs: [...npcs, ...items] });
      toast.success(`Added ${items.length} NPCs`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate NPCs"));
    }
  }

  return (
    <div>
      <SectionIntro title="NPCs">
        Name a handful of figures the DM can lean on. Each should sit somewhere in the world and,
        ideally, have a <strong className="text-text">bond</strong> to the party or the central
        conflict. These are narrative characters — combatants go in the next step.
      </SectionIntro>

      <RepeatableSection
        noun="NPCs"
        items={npcs}
        onChange={(npcs) => setField({ npcs })}
        makeEmpty={emptyNpc}
        aiSlot={
          <AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />
        }
        titleOf={(n) => n.name}
        renderItem={(npc, update) => (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <LabeledInput
                label="Name"
                placeholder="e.g. Marlena Voss"
                value={npc.name}
                onChange={(v) => update({ name: v })}
              />
              <LabeledInput
                label="Race"
                placeholder="e.g. Half-elf"
                value={npc.race}
                onChange={(v) => update({ race: v })}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <LabeledInput
                label="Role"
                placeholder="e.g. Harbourmaster"
                value={npc.role}
                onChange={(v) => update({ role: v })}
              />
              <LabeledInput
                label="Location"
                placeholder="Where they're usually found"
                value={npc.location}
                onChange={(v) => update({ location: v })}
              />
            </div>
            <LabeledInput
              label="Bond"
              placeholder="Their hook or tie to the party / conflict"
              value={npc.bond}
              onChange={(v) => update({ bond: v })}
            />
            <LabeledTextarea
              label="Description"
              placeholder="Appearance, manner, secrets..."
              value={npc.description}
              onChange={(v) => update({ description: v })}
            />
          </div>
        )}
      />
    </div>
  );
}
