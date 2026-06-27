package com.dungeon.master.model.entity;

import com.dungeon.master.model.enums.CheckKind;
import com.dungeon.master.model.enums.RollMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An ability check the DM has requested but the player has not yet rolled. Persisted (rather
 * than held in memory) so it survives reconnects and is the authoritative source of the DC.
 * Keyed by {@code (sessionId, playerId)} — at most one pending check per player. Collaborative
 * rounds tag every flagged player with the same {@code roundToken} so their resolution can be
 * gated until all are rolled.
 */
@Entity
@Table(name = "pending_checks")
@IdClass(PendingCheck.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingCheck {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(nullable = false, length = 10)
    private String ability;

    @Column(nullable = false)
    private int dc;

    @Column(length = 50)
    private String skill;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "turn_event_id")
    private UUID turnEventId;

    @Column(name = "round_token")
    private UUID roundToken;

    /**
     * The DM's situational roll mode for this check (ADVANTAGE/DISADVANTAGE), applied when the
     * player rolls. Null/NORMAL means the DM granted no situational edge or hindrance.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "dm_mode", length = 20)
    private RollMode dmMode;

    /**
     * The kind of check (STANDARD / GROUP / CONTEST). Drives how {@code CheckService} resolves it:
     * STANDARD compares total vs DC; GROUP applies the half-the-party rule across a shared
     * {@code roundToken}; CONTEST has the engine roll an opposed NPC side ({@code 1d20 + targetMod}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "check_kind", length = 20, nullable = false)
    @Builder.Default
    private CheckKind checkKind = CheckKind.STANDARD;

    /** CONTEST only: the NPC/defender's flat modifier; their side rolls {@code 1d20 + targetMod}. */
    @Column(name = "target_mod")
    private Integer targetMod;

    /** CONTEST only: the in-fiction label of the opposed party (e.g. "the guard"). */
    @Column(name = "target_label", length = 255)
    private String targetLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Composite primary key for {@link PendingCheck}. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID sessionId;
        private UUID playerId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(sessionId, key.sessionId) && Objects.equals(playerId, key.playerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionId, playerId);
        }
    }
}
