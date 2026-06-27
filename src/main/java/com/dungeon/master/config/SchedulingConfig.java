package com.dungeon.master.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Enables Spring scheduling and provides a small {@link TaskScheduler} used by the
 * collaborative {@code RoundCollector} to flush debounced rounds off-thread and by the
 * {@code CheckService} GROUP-abandonment sweep. Spring Boot does not provide a scheduler bean
 * unless one is declared, so we add it explicitly. Pool size is 4 so the sweep (which blocks a
 * thread per stale group while it streams the LLM narration) does not starve the RoundCollector
 * debounce flushes that share this scheduler.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("round-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
