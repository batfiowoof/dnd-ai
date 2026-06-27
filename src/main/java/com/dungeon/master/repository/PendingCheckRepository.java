package com.dungeon.master.repository;

import com.dungeon.master.model.entity.PendingCheck;
import com.dungeon.master.model.enums.CheckKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PendingCheckRepository extends JpaRepository<PendingCheck, PendingCheck.Key> {

    Optional<PendingCheck> findBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    List<PendingCheck> findBySessionId(UUID sessionId);

    List<PendingCheck> findBySessionIdAndRoundToken(UUID sessionId, UUID roundToken);

    long countBySessionIdAndRoundToken(UUID sessionId, UUID roundToken);

    /**
     * Atomically delete a player's pending check, returning the number of rows actually removed.
     * A bulk modifying delete (not a derived find-then-remove) so the returned value is the real
     * JDBC update count — this is what lets {@code CheckService} use it as a claim-gate: two rapid
     * roll submits race on the same row and only the winner gets a non-zero count. Runs in its own
     * small transaction so the caller (which then streams a multi-second LLM narration) holds no DB
     * connection. Callers that don't need the count (e.g. turn changes) can ignore the return.
     */
    @Modifying
    @Transactional
    @Query("delete from PendingCheck pc where pc.sessionId = :sessionId and pc.playerId = :playerId")
    int deleteBySessionIdAndPlayerId(@Param("sessionId") UUID sessionId, @Param("playerId") UUID playerId);

    /**
     * Atomically delete every remaining pending check sharing a round token, returning the JDBC
     * update count. The group-abandonment sweep uses this as a claim-gate (mirroring the per-player
     * gate above): a non-zero count means THIS sweep won the group; zero means the last roller (or a
     * prior sweep) already resolved it, so the sweep must stay silent and never double-narrate.
     */
    @Modifying
    @Transactional
    @Query("delete from PendingCheck pc where pc.sessionId = :sessionId and pc.roundToken = :roundToken")
    int deleteBySessionIdAndRoundToken(@Param("sessionId") UUID sessionId,
                                       @Param("roundToken") UUID roundToken);

    /** GROUP pending checks created before {@code cutoff} — feeds the abandonment timeout sweep. */
    List<PendingCheck> findByCheckKindAndCreatedAtBefore(CheckKind checkKind, LocalDateTime cutoff);
}
