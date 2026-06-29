package com.dungeon.master.service.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streams DM narration from the chat model with transient-failure resilience. Cloud chat
 * (OpenRouter) has transient failures a local model didn't — chiefly 429 rate-limit/congestion —
 * so each streamed call retries with exponential backoff, but ONLY before the first token reaches
 * the client (a re-subscribe re-runs the prompt from scratch and would otherwise duplicate
 * narration). The original error is propagated so the caller's {@code @CircuitBreaker} counts it.
 */
@Component
@RequiredArgsConstructor
public class ResilientChatStreamer {

    // Backoff: 2s, 4s, 8s (capped at 10s).
    private static final int AI_MAX_RETRIES = 3;
    private static final Duration AI_RETRY_MIN_BACKOFF = Duration.ofSeconds(2);
    private static final Duration AI_RETRY_MAX_BACKOFF = Duration.ofSeconds(10);

    private final ChatClient dmChatClient;
    private final ChatModel chatModel;

    /** Stream a plain user message through the high-level {@link ChatClient}, returning the full text. */
    public String streamToString(String userMessage, Consumer<String> onChunk) {
        StringBuilder assembled = new StringBuilder();
        dmChatClient.prompt()
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    assembled.append(chunk);
                    if (onChunk != null) {
                        onChunk.accept(chunk);
                    }
                })
                .retryWhen(Retry.backoff(AI_MAX_RETRIES, AI_RETRY_MIN_BACKOFF)
                        .maxBackoff(AI_RETRY_MAX_BACKOFF)
                        // Retry only transient failures, and only while nothing has streamed yet —
                        // a re-subscribe re-runs the prompt from scratch, so retrying after tokens
                        // have reached the client would duplicate narration.
                        .filter(t -> assembled.length() == 0 && isRetryable(t))
                        // Propagate the ORIGINAL error (not Reactor's RetryExhaustedException) so the
                        // @CircuitBreaker counts it and the fallback fires with the real cause.
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .blockLast();
        return assembled.toString();
    }

    /**
     * Stream a {@link Prompt} through the raw {@link ChatModel}, forwarding any assistant TEXT to
     * {@code onChunk} and returning the aggregated {@link ChatResponse} (so the caller can inspect
     * tool calls). A tool-call response carries no text, so the decision round forwards nothing. The
     * same "retry only before the first token" guard as {@link #streamToString} keeps a transient
     * 429 from duplicating already-streamed narration; tool execution happens OUTSIDE this method, so
     * a retried decision round never re-rolls.
     */
    public ChatResponse streamAggregate(Prompt prompt, StringBuilder assembled, Consumer<String> onChunk) {
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        new MessageAggregator().aggregate(
                chatModel.stream(prompt)
                        .doOnNext(cr -> {
                            String text = textOf(cr);
                            if (text != null && !text.isEmpty()) {
                                assembled.append(text);
                                if (onChunk != null) {
                                    onChunk.accept(text);
                                }
                            }
                        })
                        .retryWhen(Retry.backoff(AI_MAX_RETRIES, AI_RETRY_MIN_BACKOFF)
                                .maxBackoff(AI_RETRY_MAX_BACKOFF)
                                .filter(t -> assembled.length() == 0 && isRetryable(t))
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure())),
                aggregated::set)
                .blockLast();
        return aggregated.get();
    }

    private static String textOf(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return null;
        }
        return cr.getResult().getOutput().getText();
    }

    /** Transient cloud failures worth retrying: 429 rate limits, 5xx, and connection blips. */
    private static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
        }
        return t instanceof TransientAiException
                || t instanceof WebClientRequestException
                || t instanceof java.io.IOException;
    }
}
