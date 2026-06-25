package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.InventoryItem;
import com.dungeon.master.model.dto.SpellSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-session runtime state for a player. The id IS the {@link Player} id
 * (shared primary key) — one runtime row per session player. Seeded from the
 * linked {@code Character} template on join; mutated only by the backend.
 */
@Entity
@Table(name = "player_runtime_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerRuntimeState {

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "current_hp", nullable = false)
    private int currentHp;

    @Column(name = "max_hp", nullable = false)
    private int maxHp;

    @Column(name = "temp_hp", nullable = false)
    @Builder.Default
    private int tempHp = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spell_slots", columnDefinition = "jsonb")
    @Builder.Default
    private List<SpellSlot> spellSlots = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<InventoryItem> inventory = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> conditions = new ArrayList<>();

    /** Cantrips known, copied from the Character template. Display only (slot-level casting). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> cantrips = new ArrayList<>();

    /** Leveled spells known, copied from the Character template. Display only. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "known_spells", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> knownSpells = new ArrayList<>();
}
