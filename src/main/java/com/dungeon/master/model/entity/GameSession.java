package com.dungeon.master.model.entity;

import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.GameStatus;
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

    @Column(name = "collab_window_seconds", nullable = false)
    @Builder.Default
    private int collabWindowSeconds = 10;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
