package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.CustomMonster;
import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.TravelPace;
import com.dungeon.master.model.enums.TurnMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 6)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private GameStatus status = GameStatus.WAITING;

    @Column(nullable = false)
    @Builder.Default
    private int maxPlayers = 4;

    @Column(name = "current_turn_player_id")
    private UUID currentTurnPlayerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "turn_order", columnDefinition = "jsonb")
    @Builder.Default
    private List<UUID> turnOrder = new ArrayList<>();

    @Column(name = "world_setting", columnDefinition = "text")
    private String worldSetting;

    /** Authored campaign milestones; the DM may award only these (each once). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Milestone> milestones = new ArrayList<>();

    /**
     * Homebrew monster stat blocks copied from the {@link World} this session was started from. They
     * overlay the SRD {@code MonsterCatalog} for this session's combat (see {@code MonsterResolver}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_monsters", columnDefinition = "jsonb")
    @Builder.Default
    private List<CustomMonster> customMonsters = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "turn_mode", nullable = false)
    @Builder.Default
    private TurnMode turnMode = TurnMode.COLLABORATIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Difficulty difficulty = Difficulty.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "dm_style", nullable = false)
    @Builder.Default
    private DmStyle dmStyle = DmStyle.HEROIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "dm_length", nullable = false)
    @Builder.Default
    private DmLength dmLength = DmLength.STANDARD;

    @Column(name = "allow_ai_combat", nullable = false)
    @Builder.Default
    private boolean allowAiCombat = true;

    @Column(name = "allow_ai_rolls", nullable = false)
    @Builder.Default
    private boolean allowAiRolls = true;

    @Column(name = "allow_ai_disposition", nullable = false)
    @Builder.Default
    private boolean allowAiDisposition = true;

    @Column(name = "collab_window_seconds", nullable = false)
    @Builder.Default
    private int collabWindowSeconds = 10;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Narrative chronicle written when this session ends; handed to the next session. Null until then. */
    @Column(columnDefinition = "text")
    private String recap;

    @Column(name = "recap_generated_at")
    private LocalDateTime recapGeneratedAt;

    /** Soft link to the saved {@link World} this session was started from (null for free-text worlds). */
    @Column(name = "world_id")
    private UUID worldId;

    /** Soft link to the finished session this one explicitly continues (null for a fresh campaign). */
    @Column(name = "continued_from_session_id")
    private UUID continuedFromSessionId;

    /** Travel — the party's current location (a {@link World} region name); null until first placed. */
    @Column(name = "current_region")
    private String currentRegion;

    /** Travel — the subregion within {@link #currentRegion} the party is at; null when at the region
     * generally. Cleared on overland travel to a new region; set by local (intra-region) moves. */
    @Column(name = "current_subregion")
    private String currentSubregion;

    /** Travel — elapsed in-game time in minutes, advanced by each travel leg (Day N • HH:MM). */
    @Column(name = "in_game_minutes", nullable = false)
    @Builder.Default
    private long inGameMinutes = 0;

    /** Travel — the last overland pace the party chose. */
    @Enumerated(EnumType.STRING)
    @Column(name = "travel_pace", nullable = false)
    @Builder.Default
    private TravelPace travelPace = TravelPace.NORMAL;
}
