package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.dto.Quest;
import com.dungeon.master.model.dto.WorldFaction;
import com.dungeon.master.model.dto.WorldNpc;
import com.dungeon.master.model.dto.WorldRegion;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A reusable, player-authored campaign world: the structured form of what used to be a single
 * free-text {@code worldSetting} string. Owned by one user, edited in the World Builder wizard, and
 * "compiled" into a {@link GameSession} when a session is started from it (the structured sections
 * render to the session's world-setting text, milestones become leveling gates, and custom monsters
 * overlay the SRD bestiary for combat).
 *
 * <p>Structured sub-sections are stored as JSONB lists, following the same convention as
 * {@link Character}'s inventory and {@link GameSession}'s milestones.
 */
@Entity
@Table(name = "worlds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class World {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(nullable = false)
    private String name;

    /** Short one-line hook shown in the library list. */
    @Column(length = 255)
    private String tagline;

    /** The campaign one-pager: tone, themes, central conflict (markdown). */
    @Column(columnDefinition = "text")
    private String overview;

    /** Free tone tag (e.g. "Heroic", "Grimdark", "Mystery"). */
    @Column(length = 100)
    private String tone;

    /** How prevalent magic is in the setting (e.g. "Low", "Standard", "High"). */
    @Column(name = "magic_level", length = 100)
    private String magicLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<WorldRegion> regions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<WorldFaction> factions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<WorldNpc> npcs = new ArrayList<>();

    /** Homebrew combatant stat blocks; overlay the SRD catalogue once a session starts. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_monsters", columnDefinition = "jsonb")
    @Builder.Default
    private List<CustomMonster> customMonsters = new ArrayList<>();

    /** Three-act leveling gates the DM may award; reuses the session {@link Milestone} shape. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Milestone> milestones = new ArrayList<>();

    /** Authored quests — objectives, chains, twists, and rewards the DM drives during play. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Quest> quests = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
