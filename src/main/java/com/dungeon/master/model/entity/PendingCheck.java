package com.dungeon.master.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
