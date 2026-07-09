package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.ReactionPromptEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * The timed decision window for a combat reaction. Pushes the prompt privately to the reacting
 * player and arms an auto-decline so a disconnected/idle client can't wedge the paused enemy turn.
 * Mirrors {@link RoundCollector}'s schedule/cancel pattern (shared {@code taskScheduler} bean, an
 * off-thread {@link TransactionTemplate} for the timeout callback).
 *
 * <p><b>Single-instance limitation:</b> the timer map is in-memory, correct for this app's single
 * backend (same caveat as {@link RoundCollector}).</p>
 */
@Service
@Slf4j
public class ReactionWindow {

    private final TaskScheduler scheduler;
    private final TransactionTemplate tx;
    private final SimpMessagingTemplate messaging;

    /** Armed auto-decline futures keyed by session. */
    private final Map<UUID, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public ReactionWindow(@Qualifier("taskScheduler") TaskScheduler scheduler,
                          PlatformTransactionManager txManager,
                          SimpMessagingTemplate messaging) {
        this.scheduler = scheduler;
        this.tx = new TransactionTemplate(txManager);
        this.messaging = messaging;
    }

    /**
     * Push the prompt to the reacting player and schedule {@code onTimeout} (run inside its own
     * transaction) after {@code event.secondsLeft()} seconds. Any previously armed timer for the
     * session is cancelled first.
     */
    public void open(UUID sessionId, String username, ReactionPromptEvent event, Runnable onTimeout) {
        messaging.convertAndSendToUser(username, "/queue/reaction", event);
        Instant deadline = Instant.now().plusSeconds(Math.max(1, event.secondsLeft()));
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                tx.executeWithoutResult(status -> onTimeout.run());
            } catch (Exception e) {
                log.error("Reaction auto-decline failed: session={}", sessionId, e);
            }
        }, deadline);
        ScheduledFuture<?> prev = timers.put(sessionId, future);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** Cancel the armed auto-decline — the player answered in time. */
    public void cancel(UUID sessionId) {
        ScheduledFuture<?> f = timers.remove(sessionId);
        if (f != null) {
            f.cancel(false);
        }
    }
}
