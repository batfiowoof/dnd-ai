"use client";

import { useWorldDraftStore } from "@/store/worldDraftStore";
import SectionIntro from "@/components/world/shared/SectionIntro";

/** Final step — a read-only summary of everything authored before saving. */
export default function ReviewStep() {
  const draft = useWorldDraftStore();

  const subregionCount = draft.regions.reduce(
    (sum, r) => sum + (r.subregions?.length ?? 0),
    0
  );

  const counts: { label: string; value: number }[] = [
    { label: "Regions", value: draft.regions.length },
    { label: "Subregions", value: subregionCount },
    { label: "Factions", value: draft.factions.length },
    { label: "NPCs", value: draft.npcs.length },
    { label: "Monsters", value: draft.customMonsters.length },
    { label: "Milestones", value: draft.milestones.length },
  ];

  return (
    <div>
      <SectionIntro title="Review">
        Give it a final read. You can save now and keep editing later — nothing here is locked in.
      </SectionIntro>

      <div className="space-y-4">
        <div className="rounded-xl border border-border bg-surface p-5">
          <h3
            className="text-lg font-bold text-text"
            style={{ fontFamily: "var(--font-display)" }}
          >
            {draft.name || "Untitled world"}
          </h3>
          {draft.tagline && (
            <p className="mt-1 text-sm text-text-muted">{draft.tagline}</p>
          )}
          <div className="mt-2 flex flex-wrap gap-2 text-xs">
            {draft.tone && (
              <span className="rounded-full border border-border bg-bg-elevated px-2 py-0.5 text-text-muted">
                Tone: {draft.tone}
              </span>
            )}
            {draft.magicLevel && (
              <span className="rounded-full border border-border bg-bg-elevated px-2 py-0.5 text-text-muted">
                Magic: {draft.magicLevel}
              </span>
            )}
          </div>
          {draft.overview && (
            <p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-text-muted">
              {draft.overview}
            </p>
          )}
        </div>

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {counts.map((c) => (
            <div
              key={c.label}
              className="rounded-lg border border-border bg-bg-elevated py-3 text-center"
            >
              <div className="tabular text-2xl font-bold text-gold">{c.value}</div>
              <div className="text-[10px] uppercase tracking-wider text-text-muted">
                {c.label}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
