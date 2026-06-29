"use client";

import { useAlignments } from "@/hooks/useDnd5eData";
import { Field, controlClass } from "@/components/ui";
import Portrait from "@/components/Portrait";
import { useCharacterDraftStore } from "@/store/characterDraftStore";
import { StepHeading, Guide } from "@/components/character/shared";

export default function DetailsStep() {
  const alignmentsQuery = useAlignments();
  const name = useCharacterDraftStore((s) => s.name);
  const imageUrl = useCharacterDraftStore((s) => s.imageUrl);
  const alignment = useCharacterDraftStore((s) => s.alignment);
  const backstory = useCharacterDraftStore((s) => s.backstory);
  const setName = useCharacterDraftStore((s) => s.setName);
  const setImageUrl = useCharacterDraftStore((s) => s.setImageUrl);
  const setAlignment = useCharacterDraftStore((s) => s.setAlignment);
  const setBackstory = useCharacterDraftStore((s) => s.setBackstory);

  return (
    <div>
      <StepHeading>Character Details</StepHeading>
      <Guide>
        Name your character, choose an alignment that reflects their moral
        compass, and write a short backstory to bring them to life.
      </Guide>

      <div className="space-y-4">
        <div className="flex items-start gap-4">
          <Portrait src={imageUrl} name={name} size="xl" />
          <div className="flex-1 space-y-4">
            <Field label="Character Name" htmlFor="char-name" required>
              <input
                id="char-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g. Thorin Ironforge"
                className={controlClass}
              />
            </Field>
            <Field
              label="Portrait URL"
              htmlFor="char-portrait"
              hint="Optional. Paste a link to an image; leave empty for initials."
            >
              <input
                id="char-portrait"
                type="url"
                value={imageUrl}
                onChange={(e) => setImageUrl(e.target.value)}
                placeholder="https://example.com/portrait.png"
                className={controlClass}
              />
            </Field>
          </div>
        </div>

        <Field label="Alignment" htmlFor="char-alignment">
          <select
            id="char-alignment"
            value={alignment}
            onChange={(e) => setAlignment(e.target.value)}
            className={controlClass}
          >
            <option value="">-- Select Alignment --</option>
            {(alignmentsQuery.data ?? []).map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Backstory" htmlFor="char-backstory">
          <textarea
            id="char-backstory"
            value={backstory}
            onChange={(e) => setBackstory(e.target.value)}
            placeholder="Describe your character's history, motivations, and goals..."
            rows={4}
            className={controlClass}
          />
        </Field>
      </div>
    </div>
  );
}
