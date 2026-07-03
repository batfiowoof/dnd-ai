package com.dungeon.master.service.world;

import com.dungeon.master.model.dto.Quest;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.QuestType;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reproduces the quest-generation bug: a model reply where one reward item uses an out-of-enum
 * {@code kind} ("GOLD") and a quest carries a stray extra field. The strict default converter threw on
 * either, discarding every quest; the lenient parse must instead degrade the bad enum to {@code null}
 * and ignore the unknown field, after which {@link WorldSanitizer} coerces it to a valid quest.
 */
class WorldBuilderAiServiceParseTest {

    private static final ParameterizedTypeReference<List<Quest>> QUEST_LIST =
            new ParameterizedTypeReference<>() {};

    private final WorldSanitizer sanitizer = new WorldSanitizer();

    @Test
    void lenientParseSurvivesBadEnumAndStrayField() {
        // A stray "id" field (schema said additionalProperties:false) and a coin reward tagged "GOLD"
        // instead of "GEAR" — exactly what a weaker model emits and what used to nuke the whole array.
        String json = """
                [
                  {
                    "id": "not-in-schema",
                    "key": "the-gilded-leak",
                    "title": "The Gilded Leak",
                    "summary": "A reservoir of stolen souls is failing.",
                    "type": "MAIN",
                    "prerequisiteKeys": [],
                    "objectives": [
                      { "key": "find-the-crack", "description": "Trace the leak to its source", "completed": false }
                    ],
                    "twist": "The leak is sabotage, not decay.",
                    "twistTrigger": "When the party reaches the reservoir.",
                    "reward": {
                      "description": "A purse of coin and a healing draught.",
                      "items": [
                        { "name": "150 GP", "qty": 1, "kind": "GOLD", "equipped": false },
                        { "name": "Potion of Healing", "qty": 2, "kind": "POTION_HEALING", "equipped": false }
                      ],
                      "milestoneKey": "the-gilded-leak"
                    },
                    "completionImpact": "The district stabilizes.",
                    "failureImpact": "The void spreads.",
                    "dispositionShifts": [],
                    "status": "AVAILABLE"
                  }
                ]
                """;

        List<Quest> parsed = WorldBuilderAiService.parseLenient(json, QUEST_LIST);
        assertNotNull(parsed, "lenient parse must not throw on a bad enum / stray field");
        assertEquals(1, parsed.size());

        List<Quest> cleaned = sanitizer.normalizeQuests(parsed);
        assertEquals(1, cleaned.size(), "the quest must survive sanitizing");
        Quest q = cleaned.get(0);
        assertEquals("the-gilded-leak", q.key());
        assertEquals(QuestType.MAIN, q.type());
        // The "GOLD" coin (unknown enum → null) is coerced to GEAR by cleanReward; the valid item is kept.
        assertEquals(2, q.reward().items().size());
        assertEquals(ItemKind.GEAR, q.reward().items().get(0).kind());
        assertEquals("150 GP", q.reward().items().get(0).name());
        assertEquals(ItemKind.POTION_HEALING, q.reward().items().get(1).kind());
    }
}
