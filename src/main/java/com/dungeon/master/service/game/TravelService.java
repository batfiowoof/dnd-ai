package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.exception.SessionNotFoundException;
import com.dungeon.master.model.dto.RegionNode;
import com.dungeon.master.model.dto.TravelContext;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.CombatStatus;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.TravelPace;
import com.dungeon.master.repository.CombatEncounterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Out-of-combat overland travel. Validates a party's move to a route-connected location, advances the
 * in-game clock by the 5e travel time for the leg, decides whether a hostile encounter interrupts the
 * journey, updates the session's location, broadcasts the change to every client, and dispatches a
 * single DM narration of the trip (which springs combat when the encounter roll succeeds). The map's
 * location graph — adjacency and coordinates — is read from {@link TravelMapService} so validation and
 * distance always match what the player sees.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TravelService {

    /** Real-world miles per unit of normalized (0–100) map distance — tunes how long legs feel. */
    private static final double MILES_PER_UNIT = 1.2;
    /** 5e travel day is 8 hours of walking; the in-game clock consumes a full 24h day per travel day. */
    private static final int HOURS_PER_TRAVEL_DAY = 8;
    /** Base chance of a hostile encounter per leg, before pace and trip-length scaling. */
    private static final double BASE_ENCOUNTER_CHANCE = 0.12;
    private static final double MAX_ENCOUNTER_CHANCE = 0.6;

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final CombatEncounterRepository combatEncounterRepository;
    private final TravelMapService travelMapService;
    private final TurnService turnService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void travel(UUID sessionId, String username, String destinationRegion, TravelPace requestedPace) {
        GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        if (session.getStatus() != GameStatus.ACTIVE) {
            throw new IllegalStateException("The party can only travel while the game is active.");
        }
        if (combatEncounterRepository.findBySessionIdAndStatus(sessionId, CombatStatus.ACTIVE).isPresent()) {
            throw new IllegalStateException("You can't travel in the middle of combat.");
        }
        if (destinationRegion == null || destinationRegion.isBlank()) {
            throw new IllegalArgumentException("Choose a destination to travel to.");
        }

        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException(
                        "Player not found: " + username + " in session " + sessionId));

        List<RegionNode> nodes = travelMapService.nodesForSession(sessionId);
        if (nodes.isEmpty()) {
            throw new IllegalStateException("This adventure has no travel map.");
        }

        RegionNode destination = findNode(nodes, destinationRegion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "There's no location called \"" + destinationRegion + "\" on the map."));

        String fromName = session.getCurrentRegion();
        RegionNode from = fromName == null ? null : findNode(nodes, fromName).orElse(null);

        if (destination.name().equalsIgnoreCase(fromName)) {
            throw new IllegalArgumentException("The party is already at " + destination.name() + ".");
        }
        // A route must exist from where the party stands (unless they haven't been placed yet).
        if (from != null && !isConnected(from, destination.name())) {
            throw new IllegalArgumentException(
                    "There's no direct route from " + from.name() + " to " + destination.name() + ".");
        }

        TravelPace pace = requestedPace == null ? TravelPace.NORMAL : requestedPace;

        double distanceMiles = from == null ? 0 : distance(from, destination) * MILES_PER_UNIT;
        double travelDays = distanceMiles / pace.milesPerDay();
        long elapsedMinutes = Math.max(from == null ? 0 : 60, Math.round(travelDays * 24 * 60));
        String durationText = durationText(distanceMiles, travelDays, pace);

        boolean encounter = session.isAllowAiCombat() && rollEncounter(pace, travelDays);

        // Persist the new location, pace, and advanced clock.
        session.setCurrentRegion(destination.name());
        session.setTravelPace(pace);
        session.setInGameMinutes(session.getInGameMinutes() + elapsedMinutes);
        sessionRepository.save(session);

        log.info("Travel: session={}, {} -> {}, pace={}, days={}, encounter={}",
                sessionId, fromName, destination.name(), pace, String.format(Locale.ROOT, "%.1f", travelDays),
                encounter);

        // Tell every client where the party now is (updates the map pin + in-game clock immediately).
        messagingTemplate.convertAndSend("/topic/game/" + sessionId, (Object) Map.of(
                "type", "LOCATION_CHANGED",
                "sessionId", sessionId.toString(),
                "currentRegion", destination.name(),
                "fromRegion", fromName == null ? "" : fromName,
                "inGameMinutes", (Object) session.getInGameMinutes(),
                "pace", pace.name()));

        // Dispatch the DM narration of the journey (and combat, if the leg was ambushed).
        String action = travelAction(from, destination, pace, durationText);
        TravelContext travel = new TravelContext(fromName, destination.name(), durationText, encounter);
        turnService.dispatchTravelTurn(sessionId, player.getId(), player.getCharacterName(), action, travel);
    }

    /** Straight-line distance between two nodes on the normalized 0–100 canvas. */
    private static double distance(RegionNode a, RegionNode b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean isConnected(RegionNode from, String toName) {
        return from.connections().stream().anyMatch(c -> c.equalsIgnoreCase(toName));
    }

    private static Optional<RegionNode> findNode(List<RegionNode> nodes, String name) {
        return nodes.stream().filter(n -> n.name().equalsIgnoreCase(name.trim())).findFirst();
    }

    /** Encounter chance grows with pace and trip length, capped so long hauls aren't a guaranteed fight. */
    private static boolean rollEncounter(TravelPace pace, double travelDays) {
        double chance = Math.min(MAX_ENCOUNTER_CHANCE,
                BASE_ENCOUNTER_CHANCE * pace.encounterMultiplier() * Math.max(1.0, travelDays));
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    /** "about N days" for multi-day legs, "about N hours" for shorter ones. */
    private static String durationText(double distanceMiles, double travelDays, TravelPace pace) {
        if (distanceMiles <= 0) {
            return "a short while";
        }
        if (travelDays >= 1) {
            long days = Math.max(1, Math.round(travelDays));
            return "about " + days + (days == 1 ? " day" : " days");
        }
        long hours = Math.max(1, Math.round(travelDays * HOURS_PER_TRAVEL_DAY));
        return "about " + hours + (hours == 1 ? " hour" : " hours");
    }

    /** The player-facing action line shown in the log and given to the DM as the base prompt. */
    private static String travelAction(RegionNode from, RegionNode to, TravelPace pace, String durationText) {
        String origin = from == null ? "" : "from " + from.name() + " ";
        return "The party travels " + origin + "to " + to.name()
                + " at " + pace.label() + " pace (" + durationText + ").";
    }
}
