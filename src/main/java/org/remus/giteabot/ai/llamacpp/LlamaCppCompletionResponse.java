package org.remus.giteabot.ai.llamacpp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Response model for llama.cpp's OpenAI-compatible /v1/completions endpoint.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlamaCppCompletionResponse {

    private String model;
    private List<Choice> choices;
    private Usage usage;
    private ApiError error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private String text;

        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        private Integer completionTokens;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiError {
        private String message;
        private String type;
        private JsonNode code;
    }
}
