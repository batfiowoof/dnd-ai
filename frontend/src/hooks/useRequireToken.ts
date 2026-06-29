"use client";

import { useCallback } from "react";
import { useAuth } from "@/context/AuthContext";

/**
 * Returns a function that resolves a fresh JWT, throwing if unauthenticated. Used by the query/
 * mutation hooks to inject the token into each `api.ts` call's `queryFn`/`mutationFn`.
 */
export function useRequireToken() {
  const { getToken } = useAuth();
  return useCallback(async () => {
    const token = await getToken();
    if (!token) throw new Error("Not authenticated");
    return token;
  }, [getToken]);
}
