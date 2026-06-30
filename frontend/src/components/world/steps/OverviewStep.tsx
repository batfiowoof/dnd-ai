"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import { useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useGenerateOverview } from "@/hooks/useWorldQueries";
import { draftToContext } from "@/lib/worldBuilder";
import SectionIntro from "@/components/world/shared/SectionIntro";
import AiGenerateButton from "@/components/world/shared/AiGenerateButton";
import { LabeledInput, LabeledTextarea } from "@/components/world/shared/WorldField";

/** Step 1 — the campaign one-pager: name, hook, tone, magic level, and the central conflict. */
export default function OverviewStep() {
  const draft = useWorldDraftStore();
  const { name, tagline, tone, magicLevel, overview, setField } = draft;
  const toast = useToast();
  const generate = useGenerateOverview();

  async function handleGenerate() {
    try {
      const s = await generate.mutateAsync(draftToContext(draft, draft.name));
      // Fill the AI's suggestions; never overwrite the name the author already chose.
      setField({
        tagline: s.tagline ?? tagline,
        tone: s.tone ?? tone,
        magicLevel: s.magicLevel ?? magicLevel,
        overview: s.overview ?? overview,
      });
      toast.success("Overview generated");
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to generate overview"));
    }
  }

  return (
    <div>
      <SectionIntro title="Overview">
        Start with the big picture — tone, the level of magic, and the one central conflict the party
        will be pulled into. Keep it to a tight &ldquo;one-pager&rdquo; players will actually read.
      </SectionIntro>

      <div className="mb-4 flex items-center justify-between gap-2 rounded-lg border border-gold/30 bg-gold/5 px-3 py-2">
        <p className="text-xs text-text-muted">
          Give it a name or a premise, then let the AI draft the rest.
        </p>
        <AiGenerateButton onClick={handleGenerate} loading={generate.isPending} />
      </div>

      <div className="space-y-4">
        <LabeledInput
          label="World name"
          required
          placeholder="e.g. The Shattered Isles"
          value={name}
          onChange={(v) => setField({ name: v })}
        />
        <LabeledInput
          label="Tagline"
          hint="A one-line hook shown in your library."
          placeholder="e.g. Pirate kingdoms over a drowned empire"
          value={tagline}
          onChange={(v) => setField({ tagline: v })}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <LabeledInput
            label="Tone"
            placeholder="e.g. Heroic, Grimdark, Mystery"
            value={tone}
            onChange={(v) => setField({ tone: v })}
          />
          <LabeledInput
            label="Magic level"
            placeholder="e.g. Low, Standard, High"
            value={magicLevel}
            onChange={(v) => setField({ magicLevel: v })}
          />
        </div>
        <LabeledTextarea
          label="Setting & central conflict"
          hint="Themes, the world's defining truths, and what's at stake. Markdown is supported."
          placeholder={
            "Describe the world and the conflict driving the campaign...\n\nWhat's the central threat? What truths define this place?"
          }
          rows={8}
          value={overview}
          onChange={(v) => setField({ overview: v })}
        />
      </div>
    </div>
  );
}
