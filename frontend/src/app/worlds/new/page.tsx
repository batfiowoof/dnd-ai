"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import RequireAuth from "@/components/RequireAuth";
import { Brand, Button, useToast } from "@/components/ui";
import { useCreateWorld } from "@/hooks/useWorldQueries";
import { useWorldDraftStore } from "@/store/worldDraftStore";
import WorldWizard from "@/components/world/WorldWizard";
import type { WorldCreateUpdateRequest } from "@/types";

export default function NewWorldPage() {
  return (
    <RequireAuth>
      <NewWorld />
    </RequireAuth>
  );
}

function NewWorld() {
  const router = useRouter();
  const toast = useToast();
  const createMutation = useCreateWorld();

  // Fresh draft on mount, cleared again on unmount (mirrors the character wizard).
  useEffect(() => {
    useWorldDraftStore.getState().reset();
    return () => useWorldDraftStore.getState().reset();
  }, []);

  async function handleSave(request: WorldCreateUpdateRequest) {
    await createMutation.mutateAsync(request);
    toast.success("World created");
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
        <h1
          className="mb-6 text-center text-2xl font-bold text-text"
          style={{ fontFamily: "var(--font-display)" }}
        >
          Forge a New World
        </h1>
        <WorldWizard
          saveLabel="Create World"
          saving={createMutation.isPending}
          onSave={handleSave}
        />
      </div>
    </main>
  );
}
