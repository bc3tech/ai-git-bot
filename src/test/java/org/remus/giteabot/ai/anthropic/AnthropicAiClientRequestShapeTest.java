package org.remus.giteabot.ai.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the three request-building paths in {@link AnthropicAiClient}
 * actually produce the cache-controllable {@code system} shape (a
 * single-element {@code List<ContentBlock>}), rather than only asserting the
 * shape in isolation as {@link AnthropicRequestTest} does. Complements that
 * DTO-level coverage by exercising the real production call sites.
 */
class AnthropicAiClientRequestShapeTest {

    private RestClient restClient;
    private RestClient.RequestBodySpec requestBodySpec;
    private AnthropicAiClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        requestBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/messages")).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        AnthropicResponse stubResponse = new AnthropicResponse();
        stubResponse.setContent(List.of());
        when(responseSpec.body(AnthropicResponse.class)).thenReturn(stubResponse);

        client = new AnthropicAiClient(restClient, "claude-sonnet-4-20250514", 1024, true, true, false, "high");
    }

    /** Captures the AnthropicRequest actually passed to RestClient's .body(...). */
    private AnthropicRequest capturedRequest() {
        org.mockito.ArgumentCaptor<AnthropicRequest> captor =
                org.mockito.ArgumentCaptor.forClass(AnthropicRequest.class);
        org.mockito.Mockito.verify(requestBodySpec).body(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendReviewRequest_sendsSystemAsSingleTextContentBlock() {
        client.sendReviewRequest("You are a reviewer.", "claude-sonnet-4-20250514",
                1024, "Please review this diff.");

        AnthropicRequest request = capturedRequest();
        assertEquals(1, request.getSystem().size());
        assertEquals("text", request.getSystem().getFirst().getType());
        assertEquals("You are a reviewer.", request.getSystem().getFirst().getText());

        // legacy user message stays a plain String, unaffected by the system change
        assertInstanceOf(String.class, request.getMessages().getFirst().getContent());
        assertEquals("Please review this diff.", request.getMessages().getFirst().getContent());
    }

    @Test
    void sendChatRequest_sendsSystemAsSingleTextContentBlock() {
        List<AiMessage> history = List.of(
                AiMessage.builder().role("user").content("Hello").build()
        );

        client.sendChatRequest("You are a chat assistant.", "claude-sonnet-4-20250514",
                1024, history);

        AnthropicRequest request = capturedRequest();
        assertEquals(1, request.getSystem().size());
        assertEquals("text", request.getSystem().getFirst().getType());
        assertEquals("You are a chat assistant.", request.getSystem().getFirst().getText());
    }

    @Test
    void chatWithTools_sendsSystemAsSingleTextContentBlockAlongsideTools() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        
        List<org.remus.giteabot.ai.ToolDescriptor> tools = List.of(
            new org.remus.giteabot.ai.ToolDescriptor("test_tool", "a test tool", schema)
        );

        client.chatWithTools(List.of(), "Do the thing.", tools,
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();
        assertEquals(1, request.getSystem().size());
        assertEquals("text", request.getSystem().getFirst().getType());
        assertEquals("You are an agent.", request.getSystem().getFirst().getText());
        assertEquals(1, request.getTools().size());
    }

    // ------------------------------------------------------ prompt caching

    @Test
    void chatWithTools_cachingEnabled_marksSystemAndRollingTailBreakpoint() {
        client.chatWithTools(List.of(), "Do the thing.", List.of(tool("test_tool")),
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();

        // static head: tools + system are shared by every round -> cached once
        assertNotNull(request.getSystem().getFirst().getCacheControl(),
                "system block should carry the static-head breakpoint");
        assertEquals("ephemeral", request.getSystem().getFirst().getCacheControl().getType());

        // rolling tail: the last content block of the last message
        List<AnthropicRequest.ContentBlock> blocks = flattenBlocks(request);
        assertEquals(1, blocks.size(), "single user message should normalize to one text block");
        assertNotNull(blocks.getFirst().getCacheControl(),
                "last content block should carry the rolling breakpoint");
    }

    @Test
    void chatWithTools_cachingEnabled_placesAnchorBreakpointsWithinLookbackStride() {
        // 12 rounds of assistant(tool_use) + tool_result + the trailing user
        // message = 37 blocks: breakpoints expected at indices 36, 20 and 4.
        client.chatWithTools(rounds(12), "Do the thing.", List.of(tool("test_tool")),
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();
        List<AnthropicRequest.ContentBlock> blocks = flattenBlocks(request);
        assertEquals(37, blocks.size());

        List<Integer> marked = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).getCacheControl() != null) {
                marked.add(i);
            }
        }
        assertEquals(AnthropicAiClient.MAX_MESSAGE_BREAKPOINTS, marked.size(),
                "system breakpoint + this many message breakpoints stay within the API limit of 4");
        assertEquals(List.of(4, 20, 36), marked,
                "anchors must be spaced within the 20-block cache lookback, ending on the last block");
        for (int i = 1; i < marked.size(); i++) {
            assertTrue(marked.get(i) - marked.get(i - 1) <= 20,
                    "gap between consecutive breakpoints exceeds the 20-block lookback");
        }
    }

    @Test
    void chatWithTools_cachingEnabled_sortsToolsByNameForStablePrefix() {
        List<ToolDescriptor> tools = List.of(
                tool("zeta_tool"), tool("alpha_tool"), tool("mike_tool"));

        client.chatWithTools(List.of(), "Do the thing.", tools,
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();
        assertEquals(List.of("alpha_tool", "mike_tool", "zeta_tool"),
                request.getTools().stream().map(AnthropicRequest.Tool::getName).toList(),
                "tool definitions sit at the front of the cached prefix, so their order must be stable");
    }

    @Test
    void chatWithTools_cachingDisabled_sendsNoCacheControlAndKeepsStringContent() {
        AnthropicAiClient uncached = new AnthropicAiClient(restClient,
                "claude-sonnet-4-20250514", 1024, true, false, false, "high");

        uncached.chatWithTools(rounds(3), "Do the thing.", List.of(tool("test_tool")),
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();
        assertNull(request.getSystem().getFirst().getCacheControl());
        assertTrue(flattenBlocks(request).stream().allMatch(b -> b.getCacheControl() == null),
                "no block may carry a breakpoint when caching is off");
        assertInstanceOf(String.class, request.getMessages().getLast().getContent(),
                "message contents must stay plain strings when caching is off");
    }

    // ------------------------------------------------------ extended thinking

    @Test
    void thinkingEnabled_setsAdaptiveThinkingAndOutputConfigOnReviewRequest() {
        AnthropicAiClient thinkingClient = new AnthropicAiClient(restClient,
                "claude-sonnet-4-20250514", 8192, true, true, true, "high");

        thinkingClient.sendReviewRequest("You are a reviewer.", "claude-sonnet-4-20250514",
                8192, "Please review this diff.");

        AnthropicRequest request = capturedRequest();
        assertNotNull(request.getThinking(), "thinking block must be set when enabled");
        assertEquals("adaptive", request.getThinking().getType());
        assertNotNull(request.getOutputConfig(), "output_config must be set when enabled");
        assertEquals("high", request.getOutputConfig().getEffort());
    }

    @Test
    void thinkingEnabled_setsAdaptiveThinkingOnToolCallingRequest() {
        AnthropicAiClient thinkingClient = new AnthropicAiClient(restClient,
                "claude-sonnet-4-20250514", 8192, true, true, true, "high");

        thinkingClient.chatWithTools(List.of(), "Do the thing.", List.of(tool("test_tool")),
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();
        assertNotNull(request.getThinking(), "thinking block must be set when enabled");
        assertEquals("adaptive", request.getThinking().getType());
        assertNotNull(request.getOutputConfig(), "output_config must be set when enabled");
        assertEquals("high", request.getOutputConfig().getEffort());
    }

    @Test
    void thinkingDisabled_omitsThinkingAndOutputConfig() {
        client.sendReviewRequest("You are a reviewer.", "claude-sonnet-4-20250514",
                1024, "Please review this diff.");

        AnthropicRequest request = capturedRequest();
        assertNull(request.getThinking(), "thinking block must be null when disabled");
        assertNull(request.getOutputConfig(), "output_config must be null when disabled");
    }

    @Test
    void sendChatRequest_neverSendsAnEmptyMessageForAToolOnlyTurn() {
        // A native session replayed in legacy mode (the integration was switched, or the
        // provider advertises no native tools): the turn that only called tools has no text
        // of its own, and Anthropic rejects an empty message.
        client.sendChatRequest("You are a chat assistant.", "claude-sonnet-4-20250514",
                1024, toolOnlyHistory());

        AnthropicRequest request = capturedRequest();

        assertTrue(request.getMessages().stream().noneMatch(m -> m.getContent() == null
                        || m.getContent().toString().isBlank()),
                "every legacy message must carry text");
        assertEquals("[called cat]", request.getMessages().getFirst().getContent());
    }

    // ---------------------------------------------------------- helpers

    private static ToolDescriptor tool(String name) {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        return new ToolDescriptor(name, name + " description", schema);
    }

    /** One round = assistant turn with a tool call + the tool result message. */
    private static List<AiMessage> rounds(int count) {
        ObjectMapper mapper = new ObjectMapper();
        List<AiMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(AiMessage.builder()
                    .role("assistant")
                    .content("calling the tool")
                    .toolCalls(List.of(new ToolCall("call-" + i, "test_tool",
                            mapper.createObjectNode())))
                    .build());
            history.add(AiMessage.builder()
                    .role("tool")
                    .toolCallId("call-" + i)
                    .toolResult("result " + i)
                    .build());
        }
        return history;
    }

    private static List<AnthropicRequest.ContentBlock> flattenBlocks(AnthropicRequest request) {
        List<AnthropicRequest.ContentBlock> blocks = new ArrayList<>();
        for (AnthropicRequest.Message m : request.getMessages()) {
            if (m.getContent() instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof AnthropicRequest.ContentBlock cb) {
                        blocks.add(cb);
                    }
                }
            }
        }
        return blocks;
    }

    /** A replayed native round: an assistant turn whose only content is the tool call. */
    private static List<AiMessage> toolOnlyHistory() {
        return List.of(
                AiMessage.builder()
                        .role("assistant")
                        .toolCalls(List.of(new ToolCall("call-1", "cat",
                                new ObjectMapper().createObjectNode())))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call-1")
                        .toolResult("result 1")
                        .build());
    }
}