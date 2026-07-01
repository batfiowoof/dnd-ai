package com.dungeon.master.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_PLAYER_ACTION = "game.player.action";
    public static final String TOPIC_ROUND_ACTION = "game.round.action";
    public static final String TOPIC_DM_RESPONSE = "game.dm.response";
    public static final String TOPIC_TURN_NEXT = "game.turn.next";
    public static final String TOPIC_SESSION_EVENT = "game.session.event";
    public static final String TOPIC_COMBAT_NARRATION = "game.combat.narration";
    public static final String TOPIC_RAG_INDEX = "game.rag.index";

    /** The six game.* topics plus rag-index; used to provision matching {@code .DLT} topics. */
    private static final String[] ALL_TOPICS = {
            TOPIC_PLAYER_ACTION, TOPIC_ROUND_ACTION, TOPIC_DM_RESPONSE, TOPIC_TURN_NEXT,
            TOPIC_SESSION_EVENT, TOPIC_COMBAT_NARRATION, TOPIC_RAG_INDEX
    };

    @Bean
    public NewTopic playerActionTopic() {
        return TopicBuilder.name(TOPIC_PLAYER_ACTION)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic roundActionTopic() {
        return TopicBuilder.name(TOPIC_ROUND_ACTION)
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

    @Bean
    public NewTopic ragIndexTopic() {
        return TopicBuilder.name(TOPIC_RAG_INDEX)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * One dead-letter topic per source topic ({@code <topic>.DLT}), matching the source partition
     * count so {@link DeadLetterPublishingRecoverer} can preserve the original partition. Provisioned
     * explicitly rather than relying on broker auto-create.
     */
    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics() {
        NewTopic[] dlts = new NewTopic[ALL_TOPICS.length];
        for (int i = 0; i < ALL_TOPICS.length; i++) {
            dlts[i] = TopicBuilder.name(ALL_TOPICS[i] + ".DLT")
                    .partitions(3)
                    .replicas(1)
                    .build();
        }
        return new KafkaAdmin.NewTopics(dlts);
    }

    /**
     * Single application-wide error handler for all @KafkaListener containers (Spring Boot auto-wires
     * the lone {@code CommonErrorHandler} bean). Retries a failing record a bounded number of times
     * with a fixed backoff, then routes it to {@code <topic>.DLT} instead of blocking the partition
     * or dropping it silently. Consumers still keep their own try/catch for expected, recoverable
     * failures (e.g. a flaky LLM call) — this net is for the unexpected/poison-message cases.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }
}
