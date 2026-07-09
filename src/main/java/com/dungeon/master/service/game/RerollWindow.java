package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.RerollPromptEvent;
import com.dungeon.master.model.enums.RerollResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The timed decision window for a post-roll reroll (Heroic Inspiration / Lucky). Unlike the combat
 * {@link ReactionWindow} — which unwinds an enemy-turn engine via a pause exception — a reroll is
 * offered from inside the DM's synchronous roll tool (the LLM awaits the tool's result), so this
 * window <b>blocks the calling thread</b> on a bounded wait rather than scheduling a callback. The
 * player's {@code /roll/reroll} answer arrives on a separate STOMP thread and completes the future.
 *
 * <p><b>Single-instance limitation:</b> the pending-future map is in-memory, correct for this app's
 * single backend (same caveat as {@link ReactionWindow}). At most one reroll prompt is outstanding
 * per session because a session's DM turn is resolved serially.</p>
 */
@Service
@Slf4j
public class RerollWindow {

    private final SimpMessagingTemplate messaging;

    private record Pending(UUID promptId, CompletableFuture<RerollResource> future) {}

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public RerollWindow(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /**
     * Push the prompt to the player and block until they answer or {@code event.secondsLeft()} elapses.
     * Returns their choice, or {@link RerollResource#KEEP} on timeout/interruption (the roll stands).
     */
    public RerollResource offer(UUID sessionId, String username, RerollPromptEvent event) {
        CompletableFuture<RerollResource> future = new CompletableFuture<>();
        pending.put(sessionId, new Pending(event.promptId(), future));
        try {
            messaging.convertAndSendToUser(username, "/queue/reroll", event);
            return future.get(Math.max(1, event.secondsLeft()), TimeUnit.SECONDS);
        } catch (Exception e) {
            // Timeout, interruption, or delivery failure — the player keeps the original roll.
            return RerollResource.KEEP;
        } finally {
            pending.remove(sessionId);
        }
    }

    /** Resolve the outstanding reroll prompt for a session (called from the WS handler). */
    public void resolve(UUID sessionId, UUID promptId, RerollResource choice) {
        Pending p = pending.get(sessionId);
        if (p == null) {
            return; // window already closed (timed out or answered)
        }
        if (promptId != null && !promptId.equals(p.promptId())) {
            return; // a stale answer to a prompt that has since been replaced
        }
        p.future().complete(choice == null ? RerollResource.KEEP : choice);
    }
}
