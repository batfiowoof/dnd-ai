package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.Milestone;
import com.dungeon.master.model.entity.Character;
import com.dungeon.master.model.entity.GameSession;
import com.dungeon.master.model.entity.Player;
import com.dungeon.master.model.enums.PlayerRole;
import com.dungeon.master.repository.CharacterRepository;
import com.dungeon.master.repository.GameSessionRepository;
import com.dungeon.master.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves an authored campaign milestone into a party-wide level-up. The DM names a milestone by
 * its key; this service validates it against the session's authored list, marks it completed (so it
 * can never fire twice), and advances every party member one level — applying the automatic parts to
 * both the {@code Character} template ({@link CharacterService#applyMilestoneLevel}) and the live
 * runtime state ({@link PlayerStateService#applyLevelUpToRuntime}). ASI / new-spell choices are
 * deferred per character as pending choices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignMilestoneService {

    private final GameSessionRepository sessionRepository;
    private final PlayerRepository playerRepository;
    private final CharacterRepository characterRepository;
    private final CharacterService characterService;
    private final PlayerStateService playerStateService;

    /**
     * Outcome of an award attempt. {@code awarded} is true only when a valid, not-yet-completed
     * milestone fired; {@code note} explains rejections (unknown key, already reached) so the DM can
     * narrate honestly and never claim a level-up that didn't happen.
     */
    public record MilestoneResult(String key, String title, boolean awarded,
                                  List<String> leveledCharacters, String note) {}

    @Transactional
    public MilestoneResult completeMilestone(UUID sessionId, String key) {
        if (key == null || key.isBlank()) {
            return new MilestoneResult(key, null, false, List.of(), "no milestone key supplied");
        }
        GameSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return new MilestoneResult(key, null, false, List.of(), "no active session");
        }

        List<Milestone> milestones = new ArrayList<>(session.getMilestones());
        int idx = indexOfKey(milestones, key);
        if (idx < 0) {
            return new MilestoneResult(key, null, false, List.of(),
                    "no authored milestone with key '" + key + "'");
        }
        Milestone m = milestones.get(idx);
        if (m.completed()) {
            return new MilestoneResult(m.key(), m.title(), false, List.of(), "milestone already reached");
        }

        // Mark completed (records are immutable — replace in place) so it can never fire twice.
        milestones.set(idx, new Milestone(m.key(), m.title(), m.description(), true));
        session.setMilestones(milestones);
        sessionRepository.save(session);

        List<String> leveled = new ArrayList<>();
        for (Player p : playerRepository.findBySessionId(sessionId)) {
            if (p.getRole() != PlayerRole.PLAYER || p.getCharacterId() == null) {
                continue;
            }
            Character c = characterRepository.findById(p.getCharacterId()).orElse(null);
            if (c == null || c.getLevel() >= LevelingRules.MAX_LEVEL) {
                continue;
            }
            Character advanced = characterService.applyMilestoneLevel(c);
            try {
                playerStateService.applyLevelUpToRuntime(p.getId(), advanced);
            } catch (Exception e) {
                // No runtime state seeded (player not actively in session) — the template still advanced.
                log.debug("No runtime state to refresh for player {} on milestone: {}", p.getId(), e.getMessage());
            }
            leveled.add(advanced.getName() + " (now level " + advanced.getLevel() + ")");
        }

        log.info("Milestone '{}' reached for session={}, leveled {} character(s)",
                m.key(), sessionId, leveled.size());
        return new MilestoneResult(m.key(), m.title(), true, leveled,
                leveled.isEmpty() ? "no eligible party members to level" : "the party advanced a level");
    }

    private static int indexOfKey(List<Milestone> milestones, String key) {
        String want = key.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < milestones.size(); i++) {
            String k = milestones.get(i).key();
            if (k != null && k.trim().toLowerCase(Locale.ROOT).equals(want)) {
                return i;
            }
        }
        return -1;
    }
}
