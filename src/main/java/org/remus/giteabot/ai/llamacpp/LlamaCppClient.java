package org.remus.giteabot.ai.llamacpp;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.StreamingLineReader;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for llama.cpp server.
 * Uses llama.cpp's OpenAI-compatible completion endpoint for model routing and GBNF grammar support.
 * <p>
 * Supports GBNF grammar constraints for structured JSON output, which significantly
 * improves reliability for the agent feature compared to unconstrained generation.
 * <p>
 * See: https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md
 */
@Slf4j
public class LlamaCppClient extends AbstractAiClient {

    private final RestClient restClient;

    private final ObjectMapper jackson = AgentJackson.mapper();

    /**
     * GBNF grammar for the agent's JSON response format.
     * This grammar constrains the model to output valid JSON matching the expected schema:
     * - fileChanges: array of file modifications
     * - runTool: optional tool invocation
     * - message: optional message to the user
     * - done: boolean indicating completion
     */
    private static final String AGENT_JSON_GRAMMAR = """
            root ::= "{" ws members ws "}" ws
            members ::= pair ("," ws pair)*
            pair ::= string ws ":" ws value
            value ::= string | number | object | array | "true" | "false" | "null"
            object ::= "{" ws (members ws)? "}"
            array ::= "[" ws (value ("," ws value)*)? ws "]"
            string ::= "\\"" ([^"\\\\\\x00-\\x1f] | "\\\\" ["\\\\/bfnrt] | "\\\\u" [0-9a-fA-F]{4})* "\\""
            number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
            ws ::= [ \\t\\n\\r]*
            """;

    /**
     * Stop sequences to prevent runaway generation.
     */
    private static final List<String> STOP_SEQUENCES = List.of(
            "<|im_start|>",
            "<|im_end|>",
            "<|end|>",
            "<|eot_id|>",
            "<|endoftext|>"
    );

    public LlamaCppClient(RestClient restClient, String model, int maxTokens) {
        super(model, maxTokens);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        String prompt = buildChatPrompt(systemPrompt, userMessage);
        String grammar = shouldUseJsonGrammar(systemPrompt) ? AGENT_JSON_GRAMMAR : null;
        return doRequest(prompt, effectiveModel, maxTokens, "review", grammar);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        String prompt = buildChatPrompt(systemPrompt, conversationMessages);
        String grammar = shouldUseJsonGrammar(systemPrompt) ? AGENT_JSON_GRAMMAR : null;
        return doRequest(prompt, effectiveModel, maxTokens, "chat", grammar);
    }

    /** Returns completion metadata for the legacy JSON-in-prompt transport; native tools remain unsupported. */
    @Override
    public ChatTurn chatWithTools(List<AiMessage> conversationHistory, String newUserMessage,
                                  List<ToolDescriptor> tools, String systemPrompt,
                                  String modelOverride, Integer maxTokensOverride) {
        String effectivePrompt = resolvePrompt(systemPrompt);
        int maxTokens = maxTokensOverride != null && maxTokensOverride > 0
                ? maxTokensOverride : getMaxTokens();
        String effectiveModel = resolveModel(modelOverride);
        List<AiMessage> messages = new ArrayList<>(conversationHistory);
        messages.add(AiMessage.builder().role("user")
                .content(newUserMessage == null ? "" : newUserMessage).build());
        String grammar = shouldUseJsonGrammar(effectivePrompt) ? AGENT_JSON_GRAMMAR : null;
        LlamaCppRequest request = buildRequest(buildChatPrompt(effectivePrompt, messages),
                effectiveModel, maxTokens, "chat", grammar);
        LlamaCppCompletionResponse response = executeRequest(request);

        long inputTokens = usageTokens(response, true);
        long outputTokens = usageTokens(response, false);
        if (response.getUsage() != null) {
            reportUsage(inputTokens, outputTokens, 0L, 0L, request, response);
        } else {
            log.debug("llama.cpp chat response did not include usage counters");
        }
        LlamaCppCompletionResponse.Choice choice = firstChoice(response);
        String text = choice == null || choice.getText() == null ? "" : choice.getText();
        logTruncationIfNeeded(choice, "chat");
        return new ChatTurn(text, List.of(), mapStopReason(choice == null ? null : choice.getFinishReason()),
                inputTokens, outputTokens);
    }

    @Override
    public boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("context length")
                || normalized.contains("too long")
                || normalized.contains("maximum context")
                || normalized.contains("token limit")
                || normalized.contains("exceeds");
    }

    /**
     * Builds a chat prompt using ChatML format (used by Qwen, Mistral, etc.)
     */
    private String buildChatPrompt(String systemPrompt, String userMessage) {
        return "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n" +
                "<|im_start|>user\n" + userMessage + "<|im_end|>\n" +
                "<|im_start|>assistant\n";
    }

    /**
     * Builds a chat prompt from conversation history using ChatML format.
     */
    private String buildChatPrompt(String systemPrompt, List<AiMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n");

        for (AiMessage msg : messages) {
            sb.append("<|im_start|>").append(msg.getRole()).append("\n");
            sb.append(msg.getContent()).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    /**
     * Detects whether the system prompt is requesting JSON output for the agent.
     * If so, we enable GBNF grammar constraints for reliable structured responses.
     */
    private boolean shouldUseJsonGrammar(String systemPrompt) {
        if (systemPrompt == null) {
            return false;
        }
        String lower = systemPrompt.toLowerCase(Locale.ROOT);
        return lower.contains("respond with a json")
                || lower.contains("output json")
                || (lower.contains("output format") && lower.contains("json"))
                || lower.contains("\"filechanges\"")
                || lower.contains("\"runtool\"");
    }


    private String doRequest(String prompt, String model, int maxTokens, String context, String grammar) {
        LlamaCppRequest request = buildRequest(prompt, model, maxTokens, context, grammar);
        return extractText(request, executeRequest(request), context);
    }

    private LlamaCppRequest buildRequest(String prompt, String model, int maxTokens,
                                         String context, String grammar) {
        LlamaCppRequest.LlamaCppRequestBuilder requestBuilder = LlamaCppRequest.builder()
                .model(model)
                .prompt(prompt)
                .maxTokens(maxTokens)
                .stream(true)
                .streamOptions(LlamaCppRequest.StreamOptions.builder().includeUsage(true).build())
                .stop(STOP_SEQUENCES)
                .temperature(0.7)
                .topP(0.9)
                .topK(40)
                .repeatPenalty(1.1)
                .frequencyPenalty(0.0)
                .presencePenalty(0.0)
                .cachePrompt(true);

        if (grammar != null) {
            requestBuilder.grammar(grammar);
            log.info("llama.cpp {} request: GBNF grammar enabled for structured JSON output", context);
        }

        log.debug("llama.cpp request to /v1/completions: model={}, promptLength={}, maxTokens={}, grammar={}",
                model, prompt.length(), maxTokens, grammar != null);
        return requestBuilder.build();
    }

    private LlamaCppCompletionResponse executeRequest(LlamaCppRequest request) {
        // Stream the SSE chunks and reassemble them into a single response.
        // llama.cpp /v1/completions with stream:true emits "data: {json}" per chunk
        // (an optional trailing "data: [DONE]" marker terminates the stream).
        // Text is concatenated across chunks; usage and finish_reason are taken
        // from the final chunk.
        StringBuilder content = new StringBuilder();
        LlamaCppCompletionResponse[] lastRef = new LlamaCppCompletionResponse[1];
        LlamaCppCompletionResponse.Usage[] usageRef = new LlamaCppCompletionResponse.Usage[1];
        String[] finishReasonRef = new String[1];
        boolean[] sawDoneRef = new boolean[1];
        boolean[] sawChoiceRef = new boolean[1];

        StreamingLineReader.streamLinesUntil(restClient, "/v1/completions", request, line -> {
            if (line.startsWith(":")) {
                return true;
            }
            // Strip the SSE "data: " framing (the line reader already dropped
            // line terminators and blank lines).
            String json = line.startsWith("data:") ? line.substring(5).stripLeading() : line;
            if (json.isEmpty()) {
                return true;
            }
            if ("[DONE]".equals(json)) {
                sawDoneRef[0] = true;
                return false;
            }
            if (json.startsWith("error:")) {
                throw providerError(json.substring("error:".length()).stripLeading());
            }
            LlamaCppCompletionResponse chunk;
            try {
                chunk = jackson.readValue(json, LlamaCppCompletionResponse.class);
            } catch (JacksonException e) {
                // A malformed SSE line fails the request (do not silently skip).
                // Surfaced as an I/O error so AgentLoop.callAiWithRetry retries it
                // like any transient failure.
                throw new ResourceAccessException(
                        "Malformed llama.cpp stream line: " + e.getMessage(), new IOException(e));
            }
            if (chunk.getError() != null) {
                throw providerError(chunk.getError());
            }
            lastRef[0] = chunk;
            LlamaCppCompletionResponse.Choice choice = firstChoice(chunk);
            if (choice != null) {
                sawChoiceRef[0] = true;
                if (choice.getText() != null) {
                    content.append(choice.getText());
                }
                if (choice.getFinishReason() != null && !choice.getFinishReason().isBlank()) {
                    finishReasonRef[0] = choice.getFinishReason();
                }
            }
            if (chunk.getUsage() != null) {
                usageRef[0] = chunk.getUsage();
            }
            return true;
        });

        if (finishReasonRef[0] == null && !sawDoneRef[0]) {
            throw new ResourceAccessException(
                    "Incomplete llama.cpp stream: missing finish_reason or [DONE]");
        }
        if (!sawChoiceRef[0]) {
            throw new ResourceAccessException(
                    "Invalid llama.cpp stream: response contained no completion choice");
        }

        LlamaCppCompletionResponse source = lastRef[0];
        if (source == null) {
            throw new ResourceAccessException(
                    "Invalid llama.cpp stream: response contained no completion chunks");
        }

        LlamaCppCompletionResponse merged = new LlamaCppCompletionResponse();
        merged.setModel(source.getModel());
        LlamaCppCompletionResponse.Choice choice = new LlamaCppCompletionResponse.Choice();
        choice.setText(content.toString());
        choice.setFinishReason(finishReasonRef[0]);
        merged.setChoices(List.of(choice));
        merged.setUsage(usageRef[0]);
        return merged;
    }

    private String extractText(LlamaCppRequest request, LlamaCppCompletionResponse response, String context) {
        LlamaCppCompletionResponse.Choice choice = firstChoice(response);
        logTruncationIfNeeded(choice, context);
        if (choice == null || choice.getText() == null || choice.getText().isEmpty()) {
            log.warn("Empty response from llama.cpp server");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = choice.getText();

        if (response.getUsage() != null) {
            log.info("llama.cpp {} response: {} prompt tokens, {} generated tokens",
                    context,
                    usageTokens(response, true),
                    usageTokens(response, false));
            reportUsage(usageTokens(response, true), usageTokens(response, false), 0L, 0L, request, response);
        } else {
            log.debug("llama.cpp {} response did not include usage counters", context);
        }

        return result;
    }

    private LlamaCppCompletionResponse.Choice firstChoice(LlamaCppCompletionResponse response) {
        return response.getChoices() == null || response.getChoices().isEmpty()
                ? null : response.getChoices().getFirst();
    }

    private void logTruncationIfNeeded(LlamaCppCompletionResponse.Choice choice, String context) {
        if (choice != null && "length".equals(choice.getFinishReason())) {
            log.warn("llama.cpp {} response was truncated due to max token limit", context);
        }
    }

    private RuntimeException providerError(String payload) {
        String message = null;
        JsonNode code = null;
        try {
            JsonNode root = jackson.readTree(payload);
            JsonNode error = root.get("error");
            JsonNode source = error != null && error.isObject() ? error : root;
            JsonNode messageNode = source.get("message");
            message = messageNode == null || messageNode.isNull() ? null : messageNode.asText();
            code = source.get("code");
        } catch (JacksonException e) {
            log.debug("Unable to parse llama.cpp stream error payload: {}", e.getMessage());
        }
        return providerError(message, code);
    }

    private RuntimeException providerError(LlamaCppCompletionResponse.ApiError error) {
        return providerError(error == null ? null : error.getMessage(),
                error == null ? null : error.getCode());
    }

    private RuntimeException providerError(String message, JsonNode code) {
        String detail = "llama.cpp stream error: "
                + (message == null || message.isBlank() ? "unknown error" : message);
        if (code != null && code.isNumber() && code.asInt() < 500) {
            return new IllegalStateException(detail);
        }
        return new ResourceAccessException(detail);
    }

    private long usageTokens(LlamaCppCompletionResponse response, boolean prompt) {
        if (response.getUsage() == null) {
            return 0L;
        }
        Integer count = prompt ? response.getUsage().getPromptTokens()
                : response.getUsage().getCompletionTokens();
        return count == null ? 0L : count;
    }

    private StopReason mapStopReason(String finishReason) {
        if (finishReason == null) {
            return StopReason.OTHER;
        }
        return switch (finishReason) {
            case "stop" -> StopReason.END_TURN;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }
}
