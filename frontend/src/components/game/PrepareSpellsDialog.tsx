"use client";

import { useEffect, useState } from "react";
import Modal from "@/components/ui/Modal";
import Button from "@/components/ui/Button";
import SpellPicker from "@/components/character/shared/SpellPicker";
import type { Spell } from "@/lib/spells";

interface PrepareSpellsDialogProps {
  open: boolean;
  onClose: () => void;
  /** Every leveled spell the caster knows — the pool they prepare from. */
  knownSpells: string[];
  /** Currently prepared spells (the initial selection). */
  preparedSpells: string[];
  /** Preparation cap (spellcasting mod + level). */
  preparedMax: number;
  /** Persist the chosen prepared list (backend re-validates the cap). */
  onSave: (spells: string[]) => void;
}

/**
 * Post-long-rest spell preparation for prepared casters (cleric/druid/wizard/paladin): choose which
 * known leveled spells to have ready, up to the cap. Reuses the creation-flow {@link SpellPicker}
 * toggle grid and the Modal/Button primitives; cantrips and unprepared spells are unaffected.
 */
export default function PrepareSpellsDialog({
  open,
  onClose,
  knownSpells,
  preparedSpells,
  preparedMax,
  onSave,
}: PrepareSpellsDialogProps) {
  const [selected, setSelected] = useState<string[]>(preparedSpells);

  // Re-seed the selection whenever the dialog (re)opens with the latest prepared set.
  useEffect(() => {
    if (open) setSelected(preparedSpells);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const choices: Spell[] = knownSpells.map((name) => ({ name, level: 1 }));

  const toggle = (name: string) => {
    setSelected((cur) =>
      cur.includes(name)
        ? cur.filter((n) => n !== name)
        : cur.length >= preparedMax
          ? cur
          : [...cur, name]
    );
  };

  return (
    <Modal open={open} onClose={onClose} size="md" title="✦ Prepare Spells">
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text-muted">
          Choose the leveled spells you have prepared today. You can change these
          after a long rest. Cantrips are always available and don&apos;t count.
        </p>

        <SpellPicker
          title="Prepared spells"
          picked={selected.length}
          max={preparedMax}
          choices={choices}
          selectedNames={selected}
          onToggle={toggle}
        />

        <div className="flex justify-end gap-2">
          <Button variant="ghost" size="md" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            size="md"
            onClick={() => {
              onSave(selected);
              onClose();
            }}
          >
            Save preparation
          </Button>
        </div>
      </div>
    </Modal>
  );
}
