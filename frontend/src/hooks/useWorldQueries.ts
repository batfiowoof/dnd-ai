"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import { useRequireToken } from "@/hooks/useRequireToken";
import {
  createWorld,
  deleteWorld,
  getMyWorlds,
  getWorld,
  updateWorld,
  generateWorldOverview,
  generateWorldRegions,
  generateWorldSubregions,
  generateWorldFactions,
  generateWorldNpcs,
  generateWorldMonster,
  generateWorldMilestones,
} from "@/lib/api";
import type { WorldCreateUpdateRequest, WorldGenerateRequest } from "@/types";

/* ── Queries ─────────────────────────────────────────────────── */

export function useMyWorlds(enabled = true) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.worlds.all,
    queryFn: async () => getMyWorlds(await requireToken()),
    enabled,
  });
}

export function useWorld(id: string, enabled = true) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.worlds.byId(id),
    queryFn: async () => getWorld(await requireToken(), id),
    enabled: !!id && enabled,
  });
}

/* ── Mutations ───────────────────────────────────────────────── */

export function useCreateWorld() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: WorldCreateUpdateRequest) =>
      createWorld(await requireToken(), body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.worlds.all });
    },
  });
}

export function useUpdateWorld() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      body,
    }: {
      id: string;
      body: WorldCreateUpdateRequest;
    }) => updateWorld(await requireToken(), id, body),
    onSuccess: (_data, { id }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.worlds.all });
      queryClient.invalidateQueries({ queryKey: queryKeys.worlds.byId(id) });
    },
  });
}

export function useDeleteWorld() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => deleteWorld(await requireToken(), id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.worlds.all });
    },
  });
}

/* ── AI generation (results merge into the wizard draft, no cache) ─ */

export function useGenerateOverview() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: WorldGenerateRequest) =>
      generateWorldOverview(await requireToken(), body),
  });
}

export function useGenerateRegions() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: WorldGenerateRequest) =>
      generateWorldRegions(await requireToken(), body),
  });
}

export function useGenerateSubregions() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async ({
      region,
      body,
    }: {
      region: string;
      body: WorldGenerateRequest;
    }) => generateWorldSubregions(await requireToken(), region, body),
  });
}

export function useGenerateFactions() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: WorldGenerateRequest) =>
      generateWorldFactions(await requireToken(), body),
  });
}

export function useGenerateNpcs() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: WorldGenerateRequest) =>
      generateWorldNpcs(await requireToken(), body),
  });
}

export function useGenerateMonster() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: WorldGenerateRequest) =>
      generateWorldMonster(await requireToken(), body),
  });
}

export function useGenerateMilestones() {
  const requireToken = useRequireToken();
  return useMutation({
    mutationFn: async (body: WorldGenerateRequest) =>
      generateWorldMilestones(await requireToken(), body),
  });
}
