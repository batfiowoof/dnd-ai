"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import RequireAuth from "@/components/RequireAuth";
import { Button, Brand, Spinner, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";
import { useMyWorlds, useDeleteWorld, useCreateWorld } from "@/hooks/useWorldQueries";
import { useRequireToken } from "@/hooks/useRequireToken";
import { getWorld } from "@/lib/api";
import { downloadWorld, parseWorldImport, WORLD_FILE_SUFFIX } from "@/lib/worldTransfer";
import type { WorldSummaryDto } from "@/types";

export default function WorldsPage() {
  return (
    <RequireAuth>
      <WorldsLibrary />
    </RequireAuth>
  );
}

function WorldsLibrary() {
  const router = useRouter();
  const { username } = useAuth();
  const worldsQuery = useMyWorlds(!!username);
  const worlds = worldsQuery.data ?? [];
  const deleteMutation = useDeleteWorld();
  const createMutation = useCreateWorld();
  const requireToken = useRequireToken();
  const toast = useToast();
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (worldsQuery.isError) toast.error("Failed to load worlds");
  }, [worldsQuery.isError, toast]);

  async function handleDelete(id: string) {
    try {
      await deleteMutation.mutateAsync(id);
      setConfirmDelete(null);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, "Failed to delete world"));
    }
  }

  // Export fetches the full world (the list only holds summaries) then downloads it as JSON.
  async function handleExport(id: string, name: string) {
    try {
      const world = await getWorld(await requireToken(), id);
      downloadWorld(world);
    } catch (e: unknown) {
      toast.error(getErrorMessage(e, `Failed to export ${name}`));
    }
  }

  async function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-importing the same file
    if (!file) return;
    setImporting(true);
    try {
      const request = parseWorldImport(await file.text());
      const created = await createMutation.mutateAsync(request);
      toast.success(`Imported “${created.name}”.`);
    } catch (err: unknown) {
      toast.error(getErrorMessage(err, "Failed to import world"));
    } finally {
      setImporting(false);
    }
  }

  return (
    <main className="min-h-dvh p-4 sm:p-6">
      <div className="mx-auto max-w-4xl">
        {/* Header */}
        <div className="mb-6 flex items-center justify-between">
          <div>
            <Brand size="md" />
            <p className="mt-1 text-sm text-text-muted">
              Logged in as <span className="text-text">{username}</span>
            </p>
          </div>
          <div className="flex gap-2">
            <Button onClick={() => router.push("/")} variant="ghost" size="sm">
              Menu
            </Button>
            <Button onClick={() => router.push("/play")} variant="outline" size="sm">
              Play
            </Button>
          </div>
        </div>

        <hr className="ornament mb-6" />

        <div className="mb-6 flex items-center justify-between">
          <h2
            className="text-xl font-bold text-text"
            style={{ fontFamily: "var(--font-display)" }}
          >
            My Worlds
          </h2>
          <div className="flex gap-2">
            <input
              ref={fileInputRef}
              type="file"
              accept={`${WORLD_FILE_SUFFIX},.json`}
              onChange={handleImportFile}
              className="hidden"
            />
            <Button
              variant="outline"
              onClick={() => fileInputRef.current?.click()}
              loading={importing}
            >
              Import
            </Button>
            <Button onClick={() => router.push("/worlds/new")}>+ New World</Button>
          </div>
        </div>

        {worldsQuery.isLoading ? (
          <div className="flex items-center justify-center gap-3 py-12 text-sm text-text-muted">
            <Spinner className="text-accent" /> Loading worlds...
          </div>
        ) : worlds.length === 0 ? (
          <div className="rounded-xl border border-border-accent bg-surface p-12 text-center panel-corners">
            <p
              className="mb-2 text-lg text-text"
              style={{ fontFamily: "var(--font-display)" }}
            >
              No worlds yet
            </p>
            <p className="mb-6 text-sm text-text-muted">
              Build a campaign setting — regions, factions, NPCs, monsters, and milestones — with help
              from the AI, then play in it.
            </p>
            <Button onClick={() => router.push("/worlds/new")} size="lg">
              Forge Your First World
            </Button>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            {worlds.map((w) => (
              <WorldCard
                key={w.id}
                world={w}
                confirming={confirmDelete === w.id}
                onEdit={() => router.push(`/worlds/${w.id}/edit`)}
                onExport={() => handleExport(w.id, w.name)}
                onAskDelete={() => setConfirmDelete(w.id)}
                onCancelDelete={() => setConfirmDelete(null)}
                onConfirmDelete={() => handleDelete(w.id)}
              />
            ))}
          </div>
        )}
      </div>
    </main>
  );
}

function WorldCard({
  world,
  confirming,
  onEdit,
  onExport,
  onAskDelete,
  onCancelDelete,
  onConfirmDelete,
}: {
  world: WorldSummaryDto;
  confirming: boolean;
  onEdit: () => void;
  onExport: () => void;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onConfirmDelete: () => void;
}) {
  const counts: { label: string; value: number }[] = [
    { label: "Regions", value: world.regionCount },
    { label: "Factions", value: world.factionCount },
    { label: "NPCs", value: world.npcCount },
    { label: "Monsters", value: world.monsterCount },
    { label: "Milestones", value: world.milestoneCount },
    { label: "Quests", value: world.questCount },
  ];

  return (
    <div
      data-spotlight=""
      className="spotlight flex flex-col rounded-xl border border-border bg-surface p-5 transition hover:border-accent/50 hover:shadow-[0_0_24px_var(--color-accent-glow)]"
    >
      <div className="mb-3 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3
            className="truncate text-lg font-bold text-text"
            style={{ fontFamily: "var(--font-display)" }}
          >
            {world.name}
          </h3>
          {world.tagline && (
            <p className="truncate text-sm text-text-muted">{world.tagline}</p>
          )}
          {world.tone && (
            <span className="mt-1 inline-block rounded-full border border-gold/40 bg-gold/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-gold">
              {world.tone}
            </span>
          )}
        </div>
        <div className="flex flex-shrink-0 gap-1">
          <button
            onClick={onEdit}
            className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-accent"
          >
            Edit
          </button>
          <button
            onClick={onExport}
            title="Download this world as a file"
            className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-accent"
          >
            Export
          </button>
          {confirming ? (
            <div className="flex gap-1">
              <button
                onClick={onConfirmDelete}
                className="cursor-pointer rounded bg-accent-dark px-2 py-1 text-xs text-white transition hover:bg-accent"
              >
                Confirm
              </button>
              <button
                onClick={onCancelDelete}
                className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-text"
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              onClick={onAskDelete}
              className="cursor-pointer rounded px-2 py-1 text-xs text-text-muted transition hover:text-accent"
            >
              Delete
            </button>
          )}
        </div>
      </div>

      <div className="mt-auto grid grid-cols-3 gap-1">
        {counts.map((c) => (
          <div
            key={c.label}
            className="rounded-lg border border-border bg-bg-elevated py-2 text-center"
            title={c.label}
          >
            <div className="tabular text-base font-bold text-gold">{c.value}</div>
            <div className="text-[9px] uppercase tracking-wider text-text-muted">
              {c.label}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
