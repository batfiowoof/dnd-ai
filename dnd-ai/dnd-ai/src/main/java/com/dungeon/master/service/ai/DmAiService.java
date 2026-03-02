package com.dungeon.master.service.ai;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmAiService {

    private final ChatClient dmChatClient;
    private final RagService ragService;

    @CircuitBreaker(name = "aiService", fallbackMethod = "fallbackResponse")
    public String generateResponse(UUID sessionId, String playerName, String playerAction) {
        log.info("Generating DM response for session={}, player={}", sessionId, playerName);

        String context = ragService.buildContext(sessionId, playerAction);

        String userMessage = buildUserMessage(context, playerName, playerAction);

        String response = dmChatClient.prompt()
                .user(userMessage)
                .call()
                .content();

        log.info("DM response generated for session={}, length={}", sessionId, response.length());
        return response;
    }

    private String buildUserMessage(String context, String playerName, String action) {
        StringBuilder message = new StringBuilder();

        if (!context.isBlank()) {
            message.append("Context from the world and recent events:\n");
            message.append(context);
            message.append("\n---\n\n");
        }

        message.append("Player '").append(playerName).append("' says: ").append(action);

        return message.toString();
    }

    @SuppressWarnings("unused")
    private String fallbackResponse(UUID sessionId, String playerName, String playerAction,
                                     Throwable throwable) {
        log.error("AI service unavailable, using fallback. session={}, error={}",
                sessionId, throwable.getMessage());
        return "The Dungeon Master pauses, gathering their thoughts... " +
                "[The AI service is temporarily unavailable. " +
                "Your action '" + playerAction + "' has been recorded. " +
                "The DM will respond when service is restored. Please try again in a moment.]";
    }
}
