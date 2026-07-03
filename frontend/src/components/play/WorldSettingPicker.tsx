import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button, cn, Spinner, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useMyWorlds, useCreateWorld } from "@/hooks/useWorldQueries";
import { parseWorldImport, WORLD_FILE_SUFFIX } from "@/lib/worldTransfer";

interface WorldSettingPickerProps {
  selectedWorldId: string;
  setSelectedWorldId: (id: string) => void;
}

/**
 * World picker for session creation. Adventures run in worlds built in the World Builder, so this
 * lists the player's built worlds for selection and offers two ways to add one: "Build New" opens
 * the World Builder, and "Import" loads a previously-exported world file (creating it, then selecting
 * it). Free-text world settings were retired in favour of authored worlds.
 */
export default function WorldSettingPicker({
  selectedWorldId,
  setSelectedWorldId,
}: WorldSettingPickerProps) {
  const toast = useToast();
  const router = useRouter();
  const worldsQuery = useMyWorlds(true);
  const myWorlds = worldsQuery.data ?? [];
  const createMutation = useCreateWorld();
  const [importing, setImporting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-importing the same file
    if (!file) return;
    setImporting(true);
    try {
      const request = parseWorldImport(await file.text());
      const created = await createMutation.mutateAsync(request);
      setSelectedWorldId(created.id); // select the freshly-imported world
      toast.success(`Imported “${created.name}”.`);
    } catch (err: unknown) {
      toast.error(getErrorMessage(err, "Failed to import world"));
    } finally {
      setImporting(false);
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <label className="block text-xs font-semibold uppercase tracking-wider text-text-muted">
          World
        </label>
        <div className="flex gap-1.5">
          <input
            ref={fileInputRef}
            type="file"
            accept={`${WORLD_FILE_SUFFIX},.json`}
            onChange={handleImportFile}
            className="hidden"
          />
          <Button
            variant="ghost"
            size="sm"
            onClick={() => fileInputRef.current?.click()}
            loading={importing}
          >
            Import
          </Button>
          <Button variant="outline" size="sm" onClick={() => router.push("/worlds/new")}>
            Build New
          </Button>
        </div>
      </div>

      <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
        {worldsQuery.isLoading ? (
          <div className="flex items-center justify-center gap-2 py-6 text-xs text-text-muted">
            <Spinner className="text-accent" /> Loading your worlds...
          </div>
        ) : myWorlds.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border bg-bg-elevated p-5 text-center text-xs text-text-muted">
            <p className="mb-3">You haven&rsquo;t built any worlds yet.</p>
            <button
              onClick={() => router.push("/worlds/new")}
              className="cursor-pointer font-semibold text-accent transition hover:text-accent-light hover:underline"
            >
              Open the World Builder →
            </button>
          </div>
        ) : (
          myWorlds.map((world) => (
            <button
              key={world.id}
              onClick={() => setSelectedWorldId(world.id)}
              className={cn(
                "w-full cursor-pointer rounded-lg border px-4 py-3 text-left transition",
                selectedWorldId === world.id
                  ? "border-accent bg-accent/10"
                  : "border-border bg-bg-elevated hover:border-accent/50 hover:-translate-y-0.5"
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <div className="min-w-0">
                  <p
                    className="truncate text-sm font-semibold text-text"
                    style={{ fontFamily: "var(--font-display)" }}
                  >
                    {world.name}
                  </p>
                  {world.tagline && (
                    <p className="truncate text-xs text-text-muted">{world.tagline}</p>
                  )}
                  <p className="mt-0.5 text-[10px] uppercase tracking-wider text-text-muted">
                    {world.regionCount} regions · {world.factionCount} factions ·{" "}
                    {world.npcCount} NPCs · {world.monsterCount} monsters ·{" "}
                    {world.milestoneCount} milestones
                  </p>
                </div>
                <span
                  className={cn(
                    "h-3.5 w-3.5 flex-shrink-0 rounded-full border-2 transition",
                    selectedWorldId === world.id
                      ? "border-gold bg-gold"
                      : "border-border"
                  )}
                />
              </div>
            </button>
          ))
        )}
      </div>
    </div>
  );
}
