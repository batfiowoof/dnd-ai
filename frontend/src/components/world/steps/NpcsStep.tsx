"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { Field, controlClass, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateNpcs } from "@/hooks/useWorldQueries";
import { draftToContext, emptyNpc } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";

/** Step 4 — key NPCs, each tied to a place and ideally bound to the party. */
export default function NpcsStep() {
  const draft = useWorldDraftStore();
  const { npcs, regions, setField } = draft;
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
        aiSlot={<AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />}
        titleOf={(n) => n.name}
        renderItem={(npc, update) => {
          const region = regions.find((r) => r.name === npc.region);
          const subOptions = region?.subregions ?? [];
          return (
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
              <LabeledInput
                label="Role"
                placeholder="e.g. Harbourmaster"
                value={npc.role}
                onChange={(v) => update({ role: v })}
              />
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Region">
                  <select
                    className={controlClass}
                    value={npc.region}
                    // Changing region invalidates the old subregion — clear it.
                    onChange={(e) => update({ region: e.target.value, subregion: "" })}
                  >
                    <option value="">— Anywhere —</option>
                    {regions.map((r) => (
                      <option key={r.name} value={r.name}>
                        {r.name || "(unnamed region)"}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field
                  label="Subregion"
                  hint={
                    npc.region && subOptions.length === 0
                      ? "This region has no subregions yet."
                      : undefined
                  }
                >
                  <select
                    className={controlClass}
                    value={npc.subregion}
                    disabled={subOptions.length === 0}
                    onChange={(e) => update({ subregion: e.target.value })}
                  >
                    <option value="">— Anywhere in region —</option>
                    {subOptions.map((s) => (
                      <option key={s.name} value={s.name}>
                        {s.name}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
              <LabeledInput
                label="Specific spot"
                hint="Optional — a finer detail like “the back room of the tavern”."
                placeholder="Where exactly, within that area"
                value={npc.location}
                onChange={(v) => update({ location: v })}
              />
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
          );
        }}
      />
    </div>
  );
}
