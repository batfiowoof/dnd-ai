package com.dungeon.master.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Per-session runtime disposition for one NPC toward the party. Seeded from the authored
 * {@code WorldNpc.disposition} baseline when the session starts, then nudged by the AI DM during
 * play. NPCs have no id, so a row is keyed by session plus the NPC's name (unique case-insensitively
 * per session). This is runtime state — like {@code PlayerRuntimeState} / {@code Enemy} — not
 * authored World data.
 */
@Entity
@Table(name = "npc_disposition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NpcState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "npc_name", nullable = false)
    private String npcName;

    /** Current signed attitude score in [-100, 100]; the band is derived in code. */
    @Column(name = "disposition", nullable = false)
    private int disposition;

    /** The authored starting score, kept for reference. */
    @Column(name = "baseline", nullable = false)
    private int baseline;
}
