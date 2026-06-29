"use client";

import { useRef, useState } from "react";
import type { Token } from "@/types";
import { Button, Spinner, useToast } from "@/components/ui";
import { getErrorMessage } from "@/lib/errors";

/**
 * Battle-map header: the "Battlefield" label, the active player's move counter, and the
 * host-only background-upload control (owns its own uploading / file-input state).
 */
export default function MapToolbar({
  isMyTurn,
  myToken,
  mySpeed,
  isHost,
  onUploadMap,
}: {
  isMyTurn: boolean;
  myToken: Token | null;
  mySpeed: number;
  isHost: boolean;
  onUploadMap: (file: File) => Promise<void>;
}) {
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file later
    if (!file) return;
    setUploading(true);
    try {
      await onUploadMap(file);
    } catch (err) {
      toast.error(getErrorMessage(err, "Upload failed"));
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="mb-2 flex items-center justify-between gap-2">
      <span className="font-display text-xs font-bold uppercase tracking-wider text-accent">
        Battlefield
      </span>
      <div className="flex items-center gap-3">
        {isMyTurn && myToken && (
          <span className="tabular text-[11px] text-text-muted">
            Move{" "}
            <span className="text-gold">
              {Math.max(0, mySpeed - myToken.movementUsedFeet)}
            </span>
            /{mySpeed} ft
          </span>
        )}
        {isHost && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={handleFileChange}
            />
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={uploading}
              onClick={() => fileInputRef.current?.click()}
              title="Upload a battle-map background (host only)"
            >
              {uploading ? (
                <span className="inline-flex items-center gap-1.5">
                  <Spinner className="h-3 w-3 text-gold" /> Uploading…
                </span>
              ) : (
                "⬆ Map"
              )}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}
