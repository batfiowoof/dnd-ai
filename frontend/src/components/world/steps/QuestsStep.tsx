"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateQuests } from "@/hooks/useWorldQueries";
import { draftToContext, emptyQuest, emptyObjective, slugify } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import RepeatableSection from "@/components/world/shared/RepeatableSection";
import {
  LabeledInput,
  LabeledTextarea,
  LabeledSelect,
  LabeledNumber,
} from "@/components/world/shared/WorldField";
import { ITEM_KINDS, KIND_LABELS } from "@/lib/itemKinds";
import type {
  ItemKind,
  Quest,
  QuestDispositionShift,
  QuestType,
  InventoryItem,
} from "@/types";

const TYPE_OPTIONS = ["MAIN", "SIDE", "PERSONAL"] as const;
const KIND_OPTIONS = ITEM_KINDS.map((k) => KIND_LABELS[k]);
const NONE = "— None —";

/** The effective, stable key an author-authored item is referenced by (matches server-side backfill). */
const effectiveKey = (key: string, label: string) => key.trim() || slugify(label);

/**
 * Step 7 — quests: multi-stage objectives, prerequisite chains, a hidden twist, real consequences, and
 * loot/coin/level-up rewards. The richest wizard step; linked to the milestones and NPCs authored earlier.
 */
export default function QuestsStep() {
  const draft = useWorldDraftStore();
  const { quests, milestones, npcs, setField } = draft;
  const toast = useToast();
  const generate = useGenerateQuests();

  async function handleGenerate() {
    try {
      const items = await generate.mutateAsync(draftToContext(draft));
      setField({ quests: [...quests, ...items] });
      toast.success(`Added ${items.length} quests`);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate quests"));
    }
  }

  return (
    <div>
      <SectionIntro title="Quests">
        Quests are the adventures the party undertakes. Give each{" "}
        <strong className="text-text">objectives</strong> to work through, a hidden{" "}
        <strong className="text-text">twist</strong> the DM springs at the right moment, and{" "}
        <strong className="text-text">rewards</strong> on completion. Chain them with{" "}
        <strong className="text-text">prerequisites</strong>, and link a milestone to level the party.
      </SectionIntro>

      <RepeatableSection
        noun="quests"
        items={quests}
        onChange={(quests) => setField({ quests })}
        makeEmpty={emptyQuest}
        aiSlot={<AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />}
        titleOf={(q) => q.title}
        renderItem={(quest, update, index) => (
          <div className="space-y-4">
            {/* Identity */}
            <div className="grid gap-3 sm:grid-cols-[1fr_10rem]">
              <LabeledInput
                label="Title"
                placeholder="e.g. The Drowned Bell"
                value={quest.title}
                onChange={(v) => update({ title: v })}
              />
              <LabeledSelect
                label="Type"
                value={quest.type}
                options={TYPE_OPTIONS}
                onChange={(v) => update({ type: v as QuestType })}
              />
            </div>
            <LabeledTextarea
              label="Summary"
              hint="The player-facing hook — what the party is asked to do and why."
              placeholder="e.g. The harbor bell tolls on its own at midnight; the fisherfolk beg the party to find out why."
              value={quest.summary}
              onChange={(v) => update({ summary: v })}
            />

            {/* Objectives */}
            <Subsection label="Objectives" hint="Ordered steps the DM ticks off as the party clears them.">
              <RepeatableSection
                noun="objectives"
                items={quest.objectives}
                onChange={(objectives) => update({ objectives })}
                makeEmpty={emptyObjective}
                titleOf={(o) => o.description}
                renderItem={(objective, updateObjective) => (
                  <LabeledInput
                    label="Step"
                    placeholder="e.g. Dive to the sunken chapel and recover the bell's clapper"
                    value={objective.description}
                    onChange={(v) => updateObjective({ description: v })}
                  />
                )}
              />
            </Subsection>

            {/* Chain: prerequisites */}
            {quests.length > 1 && (
              <Subsection
                label="Prerequisites"
                hint="This quest stays locked until every checked quest is completed."
              >
                <PrerequisitePicker
                  self={index}
                  quests={quests}
                  selected={quest.prerequisiteKeys}
                  onChange={(prerequisiteKeys) => update({ prerequisiteKeys })}
                />
              </Subsection>
            )}

            {/* Reward */}
            <Subsection label="Reward" hint="Loot, coin, and an optional level-up when the quest completes.">
              <div className="space-y-3">
                <LabeledSelect
                  label="Level-up (link a milestone)"
                  hint="Completing this quest awards the milestone, leveling the whole party."
                  value={milestoneLabelFor(quest.reward.milestoneKey)}
                  options={[NONE, ...milestones.map((m) => m.title || "(untitled milestone)")]}
                  onChange={(label) =>
                    update({
                      reward: { ...quest.reward, milestoneKey: milestoneKeyForLabel(label) },
                    })
                  }
                />
                <LabeledTextarea
                  label="Reward description"
                  rows={2}
                  placeholder="Flavour the payoff — a title, a favor owed, a rumor..."
                  value={quest.reward.description}
                  onChange={(v) => update({ reward: { ...quest.reward, description: v } })}
                />
                <div>
                  <p className="mb-2 text-xs text-text-muted">
                    Items granted to the party. Coin is just an item — add{" "}
                    <span className="text-gold">150 GP</span> as <em>Gear</em>.
                  </p>
                  <RepeatableSection
                    noun="items"
                    items={quest.reward.items}
                    onChange={(items) => update({ reward: { ...quest.reward, items } })}
                    makeEmpty={emptyRewardItem}
                    titleOf={(it) => it.name}
                    renderItem={(item, updateItem) => (
                      <div className="grid gap-3 sm:grid-cols-[1fr_9rem_5rem]">
                        <LabeledInput
                          label="Item"
                          placeholder="e.g. Tidecaller's Locket / 150 GP"
                          value={item.name}
                          onChange={(v) => updateItem({ name: v })}
                        />
                        <LabeledSelect
                          label="Kind"
                          value={KIND_LABELS[item.kind]}
                          options={KIND_OPTIONS}
                          onChange={(label) => updateItem({ kind: kindForLabel(label) })}
                        />
                        <LabeledNumber
                          label="Qty"
                          min={1}
                          value={item.qty}
                          onChange={(v) => updateItem({ qty: v ?? 1 })}
                        />
                      </div>
                    )}
                  />
                </div>
              </div>
            </Subsection>

            {/* Twist */}
            <Subsection label="Twist" hint="A hidden complication only the DM sees, sprung at the right beat.">
              <div className="space-y-3">
                <LabeledTextarea
                  label="The twist"
                  rows={2}
                  placeholder="e.g. The bell was rung by the drowned priest — undead, and he wants the party to join him."
                  value={quest.twist}
                  onChange={(v) => update({ twist: v })}
                />
                <LabeledInput
                  label="Reveal when"
                  placeholder="e.g. once the party reaches the chapel altar"
                  value={quest.twistTrigger}
                  onChange={(v) => update({ twistTrigger: v })}
                />
              </div>
            </Subsection>

            {/* Consequences */}
            <Subsection label="Consequences" hint="How the world changes — and NPC attitudes shift — either way.">
              <div className="space-y-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <LabeledTextarea
                    label="If completed"
                    rows={2}
                    placeholder="What changes if the party succeeds."
                    value={quest.completionImpact}
                    onChange={(v) => update({ completionImpact: v })}
                  />
                  <LabeledTextarea
                    label="If failed"
                    rows={2}
                    placeholder="What changes if they fail or abandon it."
                    value={quest.failureImpact}
                    onChange={(v) => update({ failureImpact: v })}
                  />
                </div>
                <div>
                  <p className="mb-2 text-xs text-text-muted">
                    NPC attitude shifts applied when the quest completes (−100 to +100).
                  </p>
                  <RepeatableSection
                    noun="disposition shifts"
                    items={quest.dispositionShifts}
                    onChange={(dispositionShifts) => update({ dispositionShifts })}
                    makeEmpty={emptyDispositionShift}
                    titleOf={(s) => s.npcName}
                    renderItem={(shift, updateShift) => (
                      <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
                        {npcs.length > 0 ? (
                          <LabeledSelect
                            label="NPC"
                            value={shift.npcName || NONE}
                            options={[NONE, ...npcs.map((n) => n.name)]}
                            onChange={(v) => updateShift({ npcName: v === NONE ? "" : v })}
                          />
                        ) : (
                          <LabeledInput
                            label="NPC name"
                            placeholder="Name of an NPC in this world"
                            value={shift.npcName}
                            onChange={(v) => updateShift({ npcName: v })}
                          />
                        )}
                        <LabeledNumber
                          label="Change"
                          min={-100}
                          max={100}
                          value={shift.delta}
                          onChange={(v) => updateShift({ delta: v ?? 0 })}
                        />
                      </div>
                    )}
                  />
                </div>
              </div>
            </Subsection>
          </div>
        )}
      />
    </div>
  );

  /* ── milestone label ⇄ key mapping (scoped to the authored milestones) ── */

  function milestoneLabelFor(milestoneKey: string | null): string {
    if (!milestoneKey) return NONE;
    const hit = milestones.find((m) => effectiveKey(m.key, m.title) === milestoneKey);
    return hit ? hit.title || "(untitled milestone)" : NONE;
  }

  function milestoneKeyForLabel(label: string): string | null {
    if (label === NONE) return null;
    const hit = milestones.find((m) => (m.title || "(untitled milestone)") === label);
    return hit ? effectiveKey(hit.key, hit.title) : null;
  }
}

/* ── small building blocks ──────────────────────────────────────── */

/** A labeled grouping inside a quest card, keeping the dense form scannable. */
function Subsection({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-border/70 bg-bg-elevated/40 p-3">
      <div className="mb-2">
        <span className="text-[11px] font-semibold uppercase tracking-wider text-accent">{label}</span>
        {hint && <p className="mt-0.5 text-xs text-text-muted">{hint}</p>}
      </div>
      {children}
    </div>
  );
}

/** Checkbox list of the OTHER quests; checking one adds its key to this quest's prerequisites. */
function PrerequisitePicker({
  self,
  quests,
  selected,
  onChange,
}: {
  self: number;
  quests: Quest[];
  selected: string[];
  onChange: (keys: string[]) => void;
}) {
  return (
    <div className="space-y-1.5">
      {quests.map((q, i) => {
        if (i === self) return null;
        const key = effectiveKey(q.key, q.title);
        const checked = selected.includes(key);
        return (
          <label
            key={i}
            className="flex cursor-pointer items-center gap-2 text-sm text-text-muted transition hover:text-text"
          >
            <input
              type="checkbox"
              checked={checked}
              onChange={(e) =>
                onChange(
                  e.target.checked
                    ? [...selected, key]
                    : selected.filter((k) => k !== key)
                )
              }
              className="h-4 w-4 accent-[var(--color-accent)]"
            />
            {q.title || `Quest ${i + 1}`}
          </label>
        );
      })}
    </div>
  );
}

/* ── empty-item factories for the nested lists ──────────────────── */

const emptyRewardItem = (): InventoryItem => ({
  name: "",
  qty: 1,
  kind: "GEAR",
  equipped: false,
});

const emptyDispositionShift = (): QuestDispositionShift => ({ npcName: "", delta: 0 });

/** Resolve an item-kind label back to its enum code (defaults to GEAR). */
function kindForLabel(label: string): ItemKind {
  const hit = ITEM_KINDS.find((k) => KIND_LABELS[k] === label);
  return hit ?? "GEAR";
}
