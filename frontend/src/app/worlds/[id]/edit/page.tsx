"use client";

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import RequireAuth from "@/components/RequireAuth";
import { Brand, Button, Spinner, useToast } from "@/components/ui";
import { useWorld, useUpdateWorld } from "@/hooks/useWorldQueries";
import { useWorldDraftStore } from "@/store/worldDraftStore";
import { worldToDraft } from "@/lib/worldBuilder";
import WorldWizard from "@/components/world/WorldWizard";
import type { WorldCreateUpdateRequest } from "@/types";

export default function EditWorldPage() {
  return (
    <RequireAuth>
      <EditWorld />
    </RequireAuth>
  );
}

function EditWorld() {
  const router = useRouter();
  const toast = useToast();
  const params = useParams<{ id: string }>();
  const id = params.id;

  const worldQuery = useWorld(id);
  const updateMutation = useUpdateWorld();

  // Hydrate the draft once the world loads; clear it on unmount.
  useEffect(() => {
    if (worldQuery.data) {
      useWorldDraftStore.getState().hydrate(worldToDraft(worldQuery.data));
    }
  }, [worldQuery.data]);

  useEffect(() => () => useWorldDraftStore.getState().reset(), []);

  useEffect(() => {
    if (worldQuery.isError) toast.error("Failed to load world");
  }, [worldQuery.isError, toast]);

  async function handleSave(request: WorldCreateUpdateRequest) {
    await updateMutation.mutateAsync({ id, body: request });
    toast.success("World saved");
    router.push("/worlds");
  }

  return (
    <main className="min-h-dvh p-4 sm:p-6">
      <div className="mx-auto max-w-3xl">
        <div className="mb-6 flex items-center justify-between">
          <Brand size="md" />
          <Button variant="ghost" size="sm" onClick={() => router.push("/worlds")}>
            My Worlds
          </Button>
        </div>
        <hr className="ornament mb-6" />

        {worldQuery.isLoading ? (
          <div className="flex items-center justify-center gap-3 py-16 text-sm text-text-muted">
            <Spinner className="text-accent" /> Loading world...
          </div>
        ) : worldQuery.data ? (
          <>
            <h1
              className="mb-6 text-center text-2xl font-bold text-text"
              style={{ fontFamily: "var(--font-display)" }}
            >
              Edit World
            </h1>
            <WorldWizard
              saveLabel="Save Changes"
              saving={updateMutation.isPending}
              onSave={handleSave}
            />
          </>
        ) : (
          <p className="py-16 text-center text-sm text-text-muted">
            World not found.
          </p>
        )}
      </div>
    </main>
  );
}
