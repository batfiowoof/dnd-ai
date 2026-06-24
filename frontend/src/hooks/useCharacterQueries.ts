"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback } from "react";
import { useAuth } from "@/context/AuthContext";
import { queryKeys } from "@/lib/queryKeys";
import {
  createCharacter,
  deleteCharacter,
  getCharacter,
  getMyCharacters,
  updateCharacter,
} from "@/lib/api";
import type { CharacterCreateUpdateRequest } from "@/types";

function useRequireToken() {
  const { getToken } = useAuth();
  return useCallback(async () => {
    const token = await getToken();
    if (!token) throw new Error("Not authenticated");
    return token;
  }, [getToken]);
}

/* ── Queries ─────────────────────────────────────────────────── */

export function useMyCharacters(enabled = true) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.characters.all,
    queryFn: async () => getMyCharacters(await requireToken()),
    enabled,
  });
}

export function useCharacter(id: string, enabled = true) {
  const requireToken = useRequireToken();
  return useQuery({
    queryKey: queryKeys.characters.byId(id),
    queryFn: async () => getCharacter(await requireToken(), id),
    enabled: !!id && enabled,
  });
}

/* ── Mutations ───────────────────────────────────────────────── */

export function useCreateCharacter() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: CharacterCreateUpdateRequest) =>
      createCharacter(await requireToken(), body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.characters.all });
    },
  });
}

export function useUpdateCharacter() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      body,
    }: {
      id: string;
      body: CharacterCreateUpdateRequest;
    }) => updateCharacter(await requireToken(), id, body),
    onSuccess: (_data, { id }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.characters.all });
      queryClient.invalidateQueries({
        queryKey: queryKeys.characters.byId(id),
      });
    },
  });
}

export function useDeleteCharacter() {
  const requireToken = useRequireToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => deleteCharacter(await requireToken(), id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.characters.all });
    },
  });
}
