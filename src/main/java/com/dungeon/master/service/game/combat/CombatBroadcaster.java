package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.CombatActionEvent;
import com.dungeon.master.model.entity.CombatEncounter;
import com.dungeon.master.model.enums.CombatantKind;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes combat events to the session's STOMP topic. Owns the monotonic {@link #actionSeq}
 * used to order {@link CombatActionEvent}s in the client playback queue, and delegates state
 * snapshots to {@link CombatMapper}.
 */
@Component
@RequiredArgsConstructor
public class CombatBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final CombatMapper combatMapper;

    /** Monotonic per-action ordering for {@link CombatActionEvent} (client playback queue). */
    private final AtomicLong actionSeq = new AtomicLong();

    public void broadcast(UUID sessionId, Object event) {
        messagingTemplate.convertAndSend("/topic/game/" + sessionId, event);
    }

    public void broadcastAction(CombatEncounter enc, CombatantKind actorKind, String actorName,
                                String actionKind, String label, List<CombatActionEvent.Target> targets) {
        broadcast(enc.getSessionId(), new CombatActionEvent(
                CombatActionEvent.TYPE, enc.getSessionId(), actionSeq.incrementAndGet(),
                actorKind, actorName, actionKind, label, targets, combatMapper.toStateDto(enc)));
    }
}
