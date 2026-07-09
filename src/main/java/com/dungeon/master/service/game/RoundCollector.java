package com.dungeon.master.service.game;

import com.dungeon.master.kafka.event.RoundActionEvent;
import com.dungeon.master.kafka.event.RoundActionEvent.Contribution;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.RoundStatusEvent;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.repository.TurnEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * Collaborative-mode round buffer. Collects each player's latest narrative action in a short
 * debounced window and, on flush, emits a single {@link RoundActionEvent} so the DM resolves
 * the whole round in one streamed reply (exactly one LLM call per round, never raced).
 *
 * <p>The window opens on the first submission, debounces forward a little on each new
 * submission (capped at the host's {@code collabWindowSeconds}), and flushes <em>early</em>
 * once every human player has submitted or passed. Flush persists one combined
 * {@code TurnEvent} (so the round flows through history + RAG like any turn) inside its own
 * transaction, since it runs off the submitting request thread on the {@link TaskScheduler}.
 *
 * <p><b>Single-instance limitation:</b> the buffer is in-memory, which is correct for this
 * app's single backend. A multi-instance deployment would need a shared store or a delayed
 * Kafka message instead.
 */
@Service
@Slf4j
public class RoundCollector {

    /** Quiet period (seconds) the window debounces forward after each submission. */
    private static final int DEBOUNCE_SECONDS = 4;

    private final TaskScheduler scheduler;
    private final TransactionTemplate tx;
    private final TurnEventRepository turnEventRepository;
    private final GameEventProducer eventProducer;
    private final SimpMessagingTemplate messaging;

    private final Map<UUID, Round> rounds = new ConcurrentHashMap<>();

    public RoundCollector(@Qualifier("taskScheduler") TaskScheduler scheduler,
                          PlatformTransactionManager txManager,
                          TurnEventRepository turnEventRepository,
                          GameEventProducer eventProducer,
                          SimpMessagingTemplate messaging) {
        this.scheduler = scheduler;
        this.tx = new TransactionTemplate(txManager);
        this.turnEventRepository = turnEventRepository;
        this.eventProducer = eventProducer;
        this.messaging = messaging;
    }

    /** Mutable per-session round buffer. Guarded by {@code this}. */
    private static final class Round {
        final Map<UUID, Contribution> contributions = new LinkedHashMap<>();
        Instant maxDeadline;
        Instant deadline;
        ScheduledFuture<?> future;
        int expected;
    }

    /** Record (or replace) a player's action for the current round. */
    public synchronized void submit(UUID sessionId, UUID playerId, String characterName,
                                    String action, int windowSeconds, int expectedPlayers) {
        contribute(sessionId, playerId, characterName, action, windowSeconds, expectedPlayers);
    }

    /** Record that a player passes (no action) for the current round. */
    public synchronized void pass(UUID sessionId, UUID playerId, String characterName,
                                  int windowSeconds, int expectedPlayers) {
        contribute(sessionId, playerId, characterName, null, windowSeconds, expectedPlayers);
    }

    private void contribute(UUID sessionId, UUID playerId, String characterName,
                            String action, int windowSeconds, int expectedPlayers) {
        Instant now = Instant.now();
        Round r = rounds.computeIfAbsent(sessionId, k -> {
            Round nr = new Round();
            nr.maxDeadline = now.plusSeconds(Math.max(1, windowSeconds));
            return nr;
        });
        r.expected = Math.max(1, Math.max(r.expected, expectedPlayers));
        r.contributions.put(playerId, new Contribution(playerId, characterName, action));

        // Early flush once everyone has weighed in.
        if (r.contributions.size() >= r.expected) {
            broadcastStatus(sessionId, r, 0, false);
            flush(sessionId);
            return;
        }

        // Otherwise debounce the deadline forward, capped at the hard window.
        Instant debounced = now.plusSeconds(DEBOUNCE_SECONDS);
        r.deadline = debounced.isAfter(r.maxDeadline) ? r.maxDeadline : debounced;
        reschedule(sessionId, r);
        broadcastStatus(sessionId, r, secondsLeft(r, now), true);
    }

    private void reschedule(UUID sessionId, Round r) {
        if (r.future != null) {
            r.future.cancel(false);
        }
        r.future = scheduler.schedule(() -> flush(sessionId), r.deadline);
    }

    /** Flush the round: persist a combined TurnEvent and emit the {@link RoundActionEvent}. */
    public synchronized void flush(UUID sessionId) {
        Round r = rounds.remove(sessionId);
        if (r == null || r.contributions.isEmpty()) {
            return;
        }
        if (r.future != null) {
            r.future.cancel(false);
        }
        List<Contribution> actions = new ArrayList<>(r.contributions.values());

        RoundActionEvent event = tx.execute(status -> {
            int nextTurnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                    .map(e -> e.getTurnNumber() + 1)
                    .orElse(1);
            String combined = actions.stream()
                    .map(c -> c.passed()
                            ? c.characterName() + " holds back."
                            : c.characterName() + ": " + c.action())
                    .collect(Collectors.joining("\n"));
            TurnEvent turnEvent = TurnEvent.builder()
                    .sessionId(sessionId)
                    .playerId(actions.get(0).playerId())
                    .action(combined)
                    .turnNumber(nextTurnNumber)
                    .build();
            TurnEvent saved = turnEventRepository.save(turnEvent);
            return new RoundActionEvent(sessionId, nextTurnNumber, saved.getId(), actions);
        });

        log.info("Collaborative round flushed: session={}, turn={}, contributions={}",
                sessionId, event.turnNumber(), actions.size());
        eventProducer.sendRoundAction(event);
    }

    /** Number of contributions currently buffered for a session (0 if none open). */
    public synchronized int submittedCount(UUID sessionId) {
        Round r = rounds.get(sessionId);
        return r == null ? 0 : r.contributions.size();
    }

    private int secondsLeft(Round r, Instant now) {
        if (r.deadline == null) return 0;
        long s = Duration.between(now, r.deadline).getSeconds();
        return (int) Math.max(0, s);
    }

    private void broadcastStatus(UUID sessionId, Round r, int secondsLeft, boolean open) {
        messaging.convertAndSend("/topic/game/" + sessionId,
                RoundStatusEvent.of(sessionId, secondsLeft, r.contributions.size(), r.expected, open));
    }
}
