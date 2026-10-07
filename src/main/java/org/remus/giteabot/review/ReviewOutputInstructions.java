package org.remus.giteabot.review;

/**
 * App-owned instructions that ask the model for the structured review envelope parsed by
 * {@link ReviewOutputParser}. They are appended after operator-configured prompts and only at
 * generated-review entry points, never for conversational replies.
 */
public final class ReviewOutputInstructions {

    private ReviewOutputInstructions() {
    }

    private static final String ENVELOPE = """
            ## Review Output Format (required)

            Write your final review as exactly one JSON object inside a ```json fenced code block:

            ```json
            {
              "summary": "Concise overall assessment in Markdown. Do not repeat the individual findings here.",
              "findings": [
                {
                  "path": "src/main/java/Foo.java",
                  "side": "new",
                  "line": 42,
                  "severity": "MEDIUM",
                  "category": "bug",
                  "body": "Markdown explanation of the problem and a concrete suggestion."
                }
              ]
            }
            ```

            Rules for findings:
            - Emit one finding per distinct issue, anchored to the single most relevant line.
            - "path" is the file path exactly as shown in the diff header ("b/" path without the
              "b/" prefix; for deleted files use the "a/" path without the prefix).
            - "side" is "new" for added ("+") or unchanged context lines, using the line number in the
              new version of the file; "side" is "old" for removed ("-") lines, using the line number in
              the old version of the file.
            - "line" must be a line that appears inside a diff hunk. Compute it from the hunk header
              "@@ -oldStart,oldCount +newStart,newCount @@" by counting lines. Never guess.
            - For feedback that does not belong to a specific changed line, omit "path", "side" and "line".
            - "severity" is one of "BLOCKER", "MEDIUM" or "LOW"; "category" is optional.
            - Use an empty "findings" array when there are no issues.
            - Escape the JSON correctly; Markdown is allowed inside string values.
            """;

    /** Instructions for the standard (single-shot) review. */
    public static String standard() {
        return "\n\n" + ENVELOPE + "\nDo not write anything outside the fenced JSON block.";
    }

    /**
     * Instructions for the agentic review final answer.
     *
     * @param formalDecision whether a severity classification line must follow the envelope
     */
    public static String agentic(boolean formalDecision) {
        String tail = formalDecision
                ? "\nWrite nothing outside the fenced JSON block except the formal review decision "
                  + "classification line, which must come after the block as instructed."
                : "\nDo not write anything outside the fenced JSON block.";
        return "\n\n" + ENVELOPE + tail;
    }
}
