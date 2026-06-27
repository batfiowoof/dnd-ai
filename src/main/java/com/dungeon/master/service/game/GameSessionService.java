package com.dungeon.master.service.game;

import com.dungeon.master.exception.PlayerNotFoundException;
import com.dungeon.master.exception.SessionFullException;
import com.dungeon.master.exception.SessionNotFoundException;
import com.dungeon.master.kafka.event.SessionEvent;
import com.dungeon.master.kafka.producer.GameEventProducer;
import com.dungeon.master.model.dto.CreateSessionRequest;
import com.dungeon.master.model.dto.CreateSessionResponse;
import com.dungeon.master.model.dto.GameStateDto;
import com.dungeon.master.model.dto.JoinSessionRequest;
import com.dungeon.master.model.dto.PendingCheckDto;
import com.dungeon.master.model.dto.PlayerDto;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.SessionSummaryDto;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.entity.TurnEvent;
import com.dungeon.master.model.enums.Difficulty;
import com.dungeon.master.model.enums.DmLength;
import com.dungeon.master.model.enums.DmStyle;
import com.dungeon.master.model.enums.GameStatus;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.model.enums.TurnMode;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PendingCheckRepository;
import com.dungeon.master.repository.PlayerRepository;
import com.dungeon.master.repository.TurnEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameSessionService {

    /** Collaborative round window is clamped to this range (seconds). */
    private static final int MIN_COLLAB_WINDOW = 3;
    private static final int MAX_COLLAB_WINDOW = 60;
    /** Party size cap, inclusive. */
    private static final int MIN_PARTY = 1;
    private static final int MAX_PARTY = 8;

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final TurnEventRepository turnEventRepository;
    private final CharacterRepository characterRepository;
    private final PlayerStateService playerStateService;
    private final PendingCheckRepository pendingCheckRepository;
    private final DiceService diceService;
    private final GameEventProducer eventProducer;

    @Transactional
    public CreateSessionResponse createSession(CreateSessionRequest request, String username) {
        String code = generateJoinCode();
        log.info("Creating new game session with code: {}", code);

        GameSession session = GameSession.builder()
                .code(code)
                .status(GameStatus.WAITING)
                .createdBy(username)
                .worldSetting(request.worldSetting())
                .maxPlayers(clamp(request.maxPlayers() == null ? 4 : request.maxPlayers(),
                        MIN_PARTY, MAX_PARTY))
                .turnMode(request.turnMode() == null ? TurnMode.COLLABORATIVE : request.turnMode())
                .difficulty(request.difficulty() == null ? Difficulty.NORMAL : request.difficulty())
                .dmStyle(request.dmStyle() == null ? DmStyle.HEROIC : request.dmStyle())
                .dmLength(request.dmLength() == null ? DmLength.STANDARD : request.dmLength())
                .allowAiCombat(request.allowAiCombat() == null || request.allowAiCombat())
                .allowAiRolls(request.allowAiRolls() == null || request.allowAiRolls())
                .collabWindowSeconds(clamp(request.collabWindowSeconds() == null ? 10
                        : request.collabWindowSeconds(), MIN_COLLAB_WINDOW, MAX_COLLAB_WINDOW))
                .build();
        session = sessionRepository.save(session);

        Character character = characterRepository.findByIdAndOwnerUsername(request.characterId(), username)
                .orElseThrow(() -> new IllegalArgumentException("Character not found or not owned by you"));

        Player player = Player.builder()
                .username(username)
                .characterName(character.getName())
                .characterId(character.getId())
                .sessionId(session.getId())
                .role(PlayerRole.PLAYER)
                .turnIndex(0)
                .build();
        player = playerRepository.save(player);
        playerStateService.seedForPlayer(player, character);

        Player dmPlayer = Player.builder()
                .username("AI_DUNGEON_MASTER")
                .characterName("Dungeon Master")
                .sessionId(session.getId())
                .role(PlayerRole.DM_AI)
                .turnIndex(-1)
                .build();
        playerRepository.save(dmPlayer);

        session.getTurnOrder().add(player.getId());
        session.setCurrentTurnPlayerId(player.getId());
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                session.getId(), player.getId(), SessionEvent.Type.PLAYER_JOINED));

        log.info("Session created: id={}, code={}, creator={}", session.getId(), code, username);
        return new CreateSessionResponse(session.getId(), code, player.getId());
    }

    @Transactional
    public PlayerDto joinSession(UUID sessionId, JoinSessionRequest request, String username) {
        GameSession session = getSession(sessionId);

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Session is not accepting new players");
        }

        long humanPlayerCount = playerRepository.countBySessionIdAndRole(sessionId, PlayerRole.PLAYER);
        if (humanPlayerCount >= session.getMaxPlayers()) {
            throw new SessionFullException(
                    "Session is full (max " + session.getMaxPlayers() + " players)");
        }

        Optional<Player> existing = playerRepository.findBySessionIdAndUsername(sessionId, username);
        if (existing.isPresent()) {
            throw new IllegalStateException("Player already in session");
        }

        Character character = characterRepository.findByIdAndOwnerUsername(request.characterId(), username)
                .orElseThrow(() -> new IllegalArgumentException("Character not found or not owned by you"));

        Player player = Player.builder()
                .username(username)
                .characterName(character.getName())
                .characterId(character.getId())
                .sessionId(sessionId)
                .role(PlayerRole.PLAYER)
                .turnIndex(session.getTurnOrder().size())
                .build();
        player = playerRepository.save(player);
        playerStateService.seedForPlayer(player, character);

        session.getTurnOrder().add(player.getId());
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, player.getId(), SessionEvent.Type.PLAYER_JOINED));

        log.info("Player {} joined session {}", username, sessionId);
        return toPlayerDto(player);
    }

    @Transactional
    public void startSession(UUID sessionId) {
        GameSession session = getSession(sessionId);

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Session cannot be started in current state: " + session.getStatus());
        }

        if (session.getTurnOrder().isEmpty()) {
            throw new IllegalStateException("No players in session");
        }

        // Initiative mode seeds the narrative rotation by a 1d20 + DEX-mod roll (same as
        // combat), so out-of-combat play follows initiative order. Other modes keep the
        // join order. Either way currentTurnPlayerId points at the head of turnOrder.
        if (session.getTurnMode() == TurnMode.INITIATIVE) {
            session.setTurnOrder(rollNarrativeInitiative(sessionId));
        }

        session.setStatus(GameStatus.ACTIVE);
        session.setCurrentTurnPlayerId(session.getTurnOrder().isEmpty()
                ? null : session.getTurnOrder().get(0));
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, null, SessionEvent.Type.GAME_STARTED));

        log.info("Session {} started ({} mode) with {} players",
                sessionId, session.getTurnMode(), session.getTurnOrder().size());
    }

    /**
     * Roll narrative initiative (1d20 + DEX modifier) for every human player and return the
     * ordered player ids: initiative desc, then DEX mod desc, then a stable name fallback —
     * the same deterministic ranking combat uses.
     */
    private List<UUID> rollNarrativeInitiative(UUID sessionId) {
        record Seed(UUID playerId, String name, int initiative, int dexMod) {}
        List<Seed> seeds = new java.util.ArrayList<>();
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER) continue;
            int dex = dexMod(p);
            int init = diceService.roll("1d20").total() + dex;
            seeds.add(new Seed(p.getId(), p.getCharacterName(), init, dex));
        }
        seeds.sort(Comparator.comparingInt(Seed::initiative).reversed()
                .thenComparing(Comparator.comparingInt(Seed::dexMod).reversed())
                .thenComparing(s -> s.name() == null ? "" : s.name()));
        return seeds.stream().map(Seed::playerId).collect(java.util.stream.Collectors.toList());
    }

    /** DEX modifier from the player's character template (0 if none). */
    private int dexMod(Player player) {
        if (player.getCharacterId() == null) return 0;
        return characterRepository.findById(player.getCharacterId())
                .map(c -> Math.floorDiv(c.getDexterity() - 10, 2))
                .orElse(0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Transactional
    public void endSession(UUID sessionId) {
        GameSession session = getSession(sessionId);
        session.setStatus(GameStatus.FINISHED);
        sessionRepository.save(session);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, null, SessionEvent.Type.GAME_ENDED));

        log.info("Session {} ended", sessionId);
    }

    public GameSession getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
    }

    public GameSession getSessionByCode(String code) {
        return sessionRepository.findByCode(code)
                .orElseThrow(() -> new SessionNotFoundException("Session not found with code: " + code));
    }

    public GameStateDto getGameState(UUID sessionId) {
        GameSession session = getSession(sessionId);
        List<Player> players = playerRepository.findBySessionId(sessionId);
        List<PlayerDto> playerDtos = players.stream()
                .map(this::toPlayerDto)
                .toList();

        int turnNumber = turnEventRepository.findTopBySessionIdOrderByTurnNumberDesc(sessionId)
                .map(TurnEvent::getTurnNumber)
                .orElse(0);

        // Open ability checks travel on the game state so a reconnecting client can re-open its
        // roll prompt (the ROLL_REQUEST event is transient and missed on reload).
        List<PendingCheckDto> pendingChecks = pendingCheckRepository.findBySessionId(sessionId).stream()
                .map(pc -> new PendingCheckDto(
                        pc.getPlayerId(), pc.getAbility(), pc.getDc(), pc.getSkill(),
                        pc.getReason(), abilityModHint(pc.getPlayerId(), pc.getAbility()),
                        pc.getCheckKind() == null ? "STANDARD" : pc.getCheckKind().name(),
                        pc.getTargetLabel(),
                        pc.getDmMode() == null ? "NORMAL" : pc.getDmMode().name()))
                .toList();

        return new GameStateDto(
                session.getId(),
                session.getCode(),
                session.getStatus(),
                playerDtos,
                session.getCurrentTurnPlayerId(),
                turnNumber,
                session.getCreatedBy(),
                session.getWorldSetting(),
                session.getTurnMode(),
                session.getMaxPlayers(),
                session.getDifficulty(),
                session.getDmStyle(),
                session.getDmLength(),
                session.isAllowAiCombat(),
                session.isAllowAiRolls(),
                session.getCollabWindowSeconds(),
                pendingChecks);
    }

    /**
     * Informational ability-mod hint for a re-opened roll prompt (display only — the
     * authoritative modifier, including proficiency, is computed when the check resolves).
     */
    private int abilityModHint(UUID playerId, String ability) {
        try {
            PlayerRuntimeStateDto st = playerStateService.getState(playerId);
            int score = st.abilities() == null ? 10
                    : st.abilities().getOrDefault(ability.toUpperCase(java.util.Locale.ROOT), 10);
            return Math.floorDiv(score - 10, 2);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public List<PlayerDto> getPlayers(UUID sessionId) {
        getSession(sessionId);
        return playerRepository.findBySessionId(sessionId).stream()
                .map(this::toPlayerDto)
                .toList();
    }

    @Transactional
    public void removePlayer(UUID sessionId, UUID playerId, String requestingUsername) {
        GameSession session = getSession(sessionId);

        if (!requestingUsername.equals(session.getCreatedBy())) {
            throw new IllegalStateException("Only the session creator can remove players");
        }

        if (session.getStatus() != GameStatus.WAITING) {
            throw new IllegalStateException("Cannot remove players after the session has started");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));

        if (!player.getSessionId().equals(sessionId)) {
            throw new IllegalStateException("Player does not belong to this session");
        }

        if (player.getRole() == PlayerRole.DM_AI) {
            throw new IllegalStateException("Cannot remove the AI Dungeon Master");
        }

        if (player.getUsername().equals(requestingUsername)) {
            throw new IllegalStateException("Cannot remove yourself from the session");
        }

        session.getTurnOrder().remove(playerId);
        // Re-index remaining players
        List<Player> remainingPlayers = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> !p.getId().equals(playerId) && p.getRole() == PlayerRole.PLAYER)
                .toList();
        for (int i = 0; i < remainingPlayers.size(); i++) {
            remainingPlayers.get(i).setTurnIndex(i);
            playerRepository.save(remainingPlayers.get(i));
        }
        sessionRepository.save(session);
        playerRepository.delete(player);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, playerId, SessionEvent.Type.PLAYER_LEFT));

        log.info("Player {} removed from session {} by {}", playerId, sessionId, requestingUsername);
    }

    /** Sessions the user participates in (created or joined), newest first. */
    public List<SessionSummaryDto> getUserSessions(String username) {
        return playerRepository.findByUsername(username).stream()
                .map(player -> sessionRepository.findById(player.getSessionId())
                        .map(session -> toSummary(session, player, username))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(SessionSummaryDto::createdAt).reversed())
                .toList();
    }

    /** The calling user leaves a session. Creators must delete the session instead. */
    @Transactional
    public void leaveSession(UUID sessionId, String username) {
        GameSession session = getSession(sessionId);

        if (username.equals(session.getCreatedBy())) {
            throw new IllegalStateException("The session creator must delete the session, not leave it");
        }

        Player player = playerRepository.findBySessionIdAndUsername(sessionId, username)
                .orElseThrow(() -> new PlayerNotFoundException("You are not in this session"));

        UUID playerId = player.getId();
        session.getTurnOrder().remove(playerId);
        if (playerId.equals(session.getCurrentTurnPlayerId())) {
            session.setCurrentTurnPlayerId(
                    session.getTurnOrder().isEmpty() ? null : session.getTurnOrder().get(0));
        }

        List<Player> remaining = playerRepository.findBySessionId(sessionId).stream()
                .filter(p -> !p.getId().equals(playerId) && p.getRole() == PlayerRole.PLAYER)
                .toList();
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setTurnIndex(i);
            playerRepository.save(remaining.get(i));
        }
        sessionRepository.save(session);
        playerRepository.delete(player);

        eventProducer.sendSessionEvent(new SessionEvent(
                sessionId, playerId, SessionEvent.Type.PLAYER_LEFT));

        log.info("Player {} left session {}", username, sessionId);
    }

    /** Creator-only: delete a session and all of its data (cascades to players, state, etc.). */
    @Transactional
    public void deleteSession(UUID sessionId, String username) {
        GameSession session = getSession(sessionId);

        if (!username.equals(session.getCreatedBy())) {
            throw new IllegalStateException("Only the session creator can delete the session");
        }

        sessionRepository.delete(session);
        log.info("Session {} deleted by {}", sessionId, username);
    }

    private SessionSummaryDto toSummary(GameSession session, Player myPlayer, String username) {
        int playerCount = (int) playerRepository.countBySessionIdAndRole(session.getId(), PlayerRole.PLAYER);
        return new SessionSummaryDto(
                session.getId(),
                session.getCode(),
                session.getStatus(),
                title(session.getWorldSetting()),
                session.getCreatedBy(),
                session.getCreatedAt(),
                playerCount,
                username.equals(session.getCreatedBy()),
                myPlayer.getId());
    }

    /** Short, human-readable title from the first non-empty line of the world setting. */
    private String title(String worldSetting) {
        if (worldSetting == null || worldSetting.isBlank()) {
            return "Untitled adventure";
        }
        String firstLine = worldSetting.strip().lines().findFirst().orElse("").strip();
        firstLine = firstLine.replaceFirst("^#+\\s*", ""); // drop a leading markdown heading marker
        if (firstLine.isBlank()) {
            return "Untitled adventure";
        }
        return firstLine.length() > 80 ? firstLine.substring(0, 80).strip() + "…" : firstLine;
    }

    private String generateJoinCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }

    private PlayerDto toPlayerDto(Player player) {
        String imageUrl = player.getCharacterId() == null ? null
                : characterRepository.findById(player.getCharacterId())
                        .map(Character::getImageUrl)
                        .orElse(null);
        return new PlayerDto(
                player.getId(),
                player.getUsername(),
                player.getCharacterName(),
                player.getRole(),
                player.getTurnIndex(),
                player.getCharacterId(),
                imageUrl,
                player.getCharacterSheet());
    }
}
