package org.remus.giteabot.agent.shared;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.SystemPromptAssembler.PromptKind;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.remus.giteabot.review.ReviewOutputInstructions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
class SystemPromptAssemblerTest {
    private static final String CLEAN_BASE = "You are an autonomous software implementation agent.";
    private static final String BASE_WITH_MARKERS = """
            You are an autonomous software implementation agent.
            <!-- BEGIN_LEGACY_TOOL_PROTOCOL -->
            ## Output Format
            Respond with a JSON object using runTools / requestTools / requestFiles.
            <!-- END_LEGACY_TOOL_PROTOCOL -->
            """;
    private final McpToolCatalog mcpCatalog = new McpToolCatalog(List.of(
            new McpToolDefinition("github", "search_repositories",
                    "Search repositories", "Search GitHub repositories",
                    Map.of("type", "object"), "mcp:github:search_repositories")));
    private final ToolCatalog toolCatalog = new ToolCatalog(new AgentConfigProperties());
    @Test
    void legacyMode_appendsDynamicTemplateAndMcpCatalog() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                mcpCatalog, ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertTrue(out.startsWith(CLEAN_BASE));
        assertTrue(out.contains("## Output Format"));
        assertTrue(out.contains("runTools"));
        assertTrue(out.contains("## Security"));
        assertTrue(out.contains("## File Tools"));
        assertTrue(out.contains("write-file"));
        assertTrue(out.contains("Available MCP tools"));
        assertTrue(out.contains("mcp:github:search_repositories"));
    }
    @Test
    void legacyMode_whitelistRestrictsRenderedTools() {
        Set<String> allowed = Set.of("cat", "rg", "mvn");
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, allowed,
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertTrue(out.contains("`cat`"));
        assertTrue(out.contains("`rg`"));
        assertTrue(out.contains("`mvn`"));
        assertFalse(out.contains("write-file"));
        assertFalse(out.contains("patch-file"));
        assertFalse(out.contains("`gradle`"));
        assertFalse(out.contains("## File Tools"));
    }
    @Test
    void nativeMode_appendsNativeHintAndOmitsMcpCatalog() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                mcpCatalog, ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);
        assertTrue(out.startsWith(CLEAN_BASE));
        assertFalse(out.contains("## Output Format"));
        assertFalse(out.contains("runTools"));
        assertFalse(out.contains("Available MCP tools"));
        assertFalse(out.contains("mcp:github:search_repositories"));
        assertTrue(out.contains("native function-calling API"));
        assertTrue(out.contains("## Security"));
    }
    @Test
    void legacyMode_unmigratedPromptWithMarkers_stripsMarkerBlockAndRendersDynamic() {
        String out = new SystemPromptAssembler().assemble(BASE_WITH_MARKERS, toolCatalog, null,
                mcpCatalog, ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains(SystemPromptAssembler.BEGIN_MARKER));
        assertFalse(out.contains(SystemPromptAssembler.END_MARKER));
        assertFalse(out.contains("Respond with a JSON object using runTools / requestTools / requestFiles."));
        assertTrue(out.contains("## Output Format"));
    }
    @Test
    void nativeMode_unmigratedPromptWithMarkers_stripsBlockAndAppendsHint() {
        String out = new SystemPromptAssembler().assemble(BASE_WITH_MARKERS, toolCatalog, null,
                mcpCatalog, ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains(SystemPromptAssembler.BEGIN_MARKER));
        assertFalse(out.contains("## Output Format"));
        assertFalse(out.contains("runTools"));
        assertTrue(out.contains("native function-calling API"));
    }
    @Test
    void writerKind_legacyRenderedWithWriterTools() {
        String legacyOut = new SystemPromptAssembler().assemble("You are a writer.", toolCatalog, null,
                mcpCatalog, ToolingMode.LEGACY, PromptKind.WRITER_AGENT);
        assertTrue(legacyOut.contains("Reasoning tools:"));
        assertTrue(legacyOut.contains("get-issue"));
        assertTrue(legacyOut.contains("Available MCP tools"));
        String nativeOut = new SystemPromptAssembler().assemble("You are a writer.", toolCatalog, null,
                mcpCatalog, ToolingMode.NATIVE, PromptKind.WRITER_AGENT);
        assertTrue(nativeOut.contains("native function-calling API"));
        assertFalse(nativeOut.contains("Available MCP tools"));
    }
    @Test
    void reviewKind_legacyHasDistinctSingleObjectExplorationAndCompletionExamples() throws Exception {
        String protocol = new SystemPromptAssembler().assemble("You are a reviewer.", toolCatalog,
                Set.of("cat", "get-issue"), McpToolCatalog.empty(), ToolingMode.LEGACY,
                PromptKind.REVIEW_AGENT);
        assertTrue(protocol.startsWith("You are a reviewer."));
        assertTrue(protocol.contains("## Review Tool Protocol"));
        assertFalse(protocol.contains("Reasoning tools:"));
        assertFalse(protocol.contains("```"));
        String explorationExample = protocol.split("Exploration example:\\n", 2)[1].split("\\n\\n", 2)[0];
        JsonNode exploration = parseSingleObject(explorationExample);
        assertEquals("src/main/java/App.java", exploration.get("requestFiles").get(0).asText());
        assertEquals("cat", exploration.get("requestTools").get(0).get("tool").asText());
        assertTrue(exploration.get("requestTools").get(0).hasNonNull("id"));
        assertFalse(exploration.has("findings"));

        for (boolean formalDecision : List.of(false, true)) {
            String instructions = ReviewOutputInstructions.agenticLegacy(formalDecision);
            assertFalse(instructions.contains("```"));
            assertTrue(instructions.contains("Write nothing outside the single bare JSON object."));
            String completionExample = instructions.split("Completion example:\\n", 2)[1]
                    .split("\\n\\n", 2)[0];
            JsonNode completion = parseSingleObject(completionExample);
            assertTrue(completion.hasNonNull("summary"));
            assertTrue(completion.get("findings").isArray());
            assertFalse(completion.has("requestFiles"));
            assertFalse(completion.has("requestTools"));
            assertFalse(completion.has("runTools"));
            assertEquals(formalDecision, completion.has("classification"));
            if (formalDecision) {
                assertEquals(1, completion.get("classification").get("medium").asInt());
                assertTrue(instructions.contains("Never append a separate classification object."));
            }
        }
    }
    @Test
    void reviewKind_legacyRestrictsToolsToExplicitReviewAuthorization() {
        String out = new SystemPromptAssembler().assemble("Review.", toolCatalog,
                Set.of("cat", "write-file", "branch-switcher", "mvn", "grep"),
                mcpCatalog, ToolingMode.LEGACY, PromptKind.REVIEW_AGENT);
        assertTrue(out.contains("**`cat`**"));
        assertFalse(out.contains("write-file"));
        assertFalse(out.contains("branch-switcher"));
        assertFalse(out.contains("`mvn`"));
        assertFalse(out.contains("`grep`"));
        assertFalse(out.contains("get-issue"));
        assertTrue(out.contains("Available MCP tools"));
        assertTrue(out.contains("mcp:github:search_repositories"));
    }
    @Test
    void reviewKind_legacyNullOrEmptyAllowlistDeniesBuiltinTools() throws Exception {
        for (Set<String> allowed : java.util.Arrays.<Set<String>>asList(null, Set.of())) {
            String out = new SystemPromptAssembler().assemble("Review.", toolCatalog, allowed,
                    McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.REVIEW_AGENT);
            assertTrue(out.contains("no built-in review tools authorized"));
            assertFalse(out.contains("**`cat`**"));
            String example = out.split("Exploration example:\\n", 2)[1].split("\\n\\n", 2)[0];
            assertTrue(parseSingleObject(example).get("requestTools").isEmpty());
        }
    }
    @Test
    void reviewKind_nativeKeepsExistingWriterHint() {
        SystemPromptAssembler assembler = new SystemPromptAssembler();
        String writer = assembler.assemble("Review.", toolCatalog, Set.of("cat"),
                mcpCatalog, ToolingMode.NATIVE, PromptKind.WRITER_AGENT);
        String review = assembler.assemble("Review.", toolCatalog, Set.of("cat"),
                mcpCatalog, ToolingMode.NATIVE, PromptKind.REVIEW_AGENT);
        assertEquals(writer, review);
        assertTrue(review.contains("native function-calling API"));
        assertFalse(review.contains("Available MCP tools"));
        assertFalse(review.contains("Exploration example:"));
    }
    @Test
    void writerKind_legacyKeepsExistingProtocol() {
        String out = new SystemPromptAssembler().assemble("Writer.", toolCatalog, Set.of("cat"),
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.WRITER_AGENT);
        assertEquals("Writer.\n\n" + new LegacyToolProtocolRenderer()
                .renderWriterAgent(toolCatalog, Set.of("cat")), out);
        assertFalse(out.contains("## Review Tool Protocol"));
    }
    private static JsonNode parseSingleObject(String example) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (JsonParser parser = mapper.getFactory().createParser(example)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            JsonNode root = mapper.readTree(parser);
            assertNull(parser.nextToken(), "Example must contain exactly one JSON object");
            return root;
        }
    }
    @Test
    void nullPromptReturnsEmptyString() {
        assertEquals("", new SystemPromptAssembler().assemble(null, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.ISSUE_AGENT));
    }
    @Test
    void emptyMcpCatalogProducesNoMcpFragmentInLegacyMode() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains("Available MCP tools"));
    }
}
