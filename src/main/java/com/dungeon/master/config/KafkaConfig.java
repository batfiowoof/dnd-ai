package com.dungeon.master.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_PLAYER_ACTION = "game.player.action";
    public static final String TOPIC_DM_RESPONSE = "game.dm.response";
    public static final String TOPIC_TURN_NEXT = "game.turn.next";
    public static final String TOPIC_SESSION_EVENT = "game.session.event";
    public static final String TOPIC_COMBAT_NARRATION = "game.combat.narration";

    @Bean
    public NewTopic playerActionTopic() {
        return TopicBuilder.name(TOPIC_PLAYER_ACTION)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic dmResponseTopic() {
        return TopicBuilder.name(TOPIC_DM_RESPONSE)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic turnNextTopic() {
        return TopicBuilder.name(TOPIC_TURN_NEXT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic sessionEventTopic() {
        return TopicBuilder.name(TOPIC_SESSION_EVENT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic combatNarrationTopic() {
        return TopicBuilder.name(TOPIC_COMBAT_NARRATION)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
