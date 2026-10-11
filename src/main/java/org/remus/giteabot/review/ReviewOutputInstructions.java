package org.remus.giteabot.review;

/**
 * App-owned instructions that ask the model for the structured review envelope parsed by
 * {@link ReviewOutputParser}. They are appended after operator-configured prompts and only at
 * generated-review entry points, never for conversational replies.
 */
public final class ReviewOutputInstructions {

    private ReviewOutputInstructions() {
    }

    private static final String EXAMPLE_FIELDS = """
              "summary": "Concise overall assessment in Markdown. Do not repeat the individual findings here.",
              "findings": [
                {
                  "path": "src/main/java/Foo.java",
                  "side": "new",
                  "line": 42,
                  "severity": "MEDIUM",
                  "category": "bug",
                  "quote": "return value.toString();",
                  "body": "Markdown explanation of the problem and a concrete suggestion."
                }
              ]
            """;

    private static final String FINDING_RULES = """
            Rules for findings:
            - Emit one finding per distinct issue, anchored to the single most relevant line.
            - "path" is the file path exactly as shown in the diff header ("b/" path without the
              "b/" prefix; for deleted files use the "a/" path without the prefix).
            - "side" is "new" for added ("+") or unchanged context lines, using the line number in the
              new version of the file; "side" is "old" for removed ("-") lines, using the line number in
              the old version of the file.
            - "line" must be a line that appears inside a diff hunk. Compute it from the hunk header
              "@@ -oldStart,oldCount +newStart,newCount @@" by counting lines. Never guess.
            - Every line-anchored finding must include "quote": an exact, verbatim quote of the current
              code it criticizes at or near the anchored line on the indicated side. Never paraphrase code
              or quote an earlier revision. Preserve internal whitespace and escape newlines as \\n in JSON.
            - For feedback that does not belong to a specific changed line, omit "path", "side" and "line"
              and include "citations": at least one object with "path" and "quote", for example
              [{"path": "src/main/java/Foo.java", "quote": "return value.toString();"}]. Each citation
              must quote exact, verbatim current code from that file. Every citation must be verifiable.
            - Each quote must contain at least one line with at least 8 non-whitespace characters.
            - "severity" is one of "BLOCKER", "MEDIUM" or "LOW"; "category" is optional.
            - Use an empty "findings" array when there are no issues.
            - Escape the JSON correctly; Markdown is allowed inside string values.
            """;

    private static final String ENVELOPE = """
            ## Review Output Format (required)

            Write your final review as exactly one JSON object inside a ```json fenced code block:

            ```json
            {
            """ + EXAMPLE_FIELDS + "}\n```\n\n" + FINDING_RULES;

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

    /**
     * Grammar-compatible final-answer instructions for full legacy agentic reviews.
     *
     * @param formalDecision whether to include severity counts inside the completed envelope
     */
    public static String agenticLegacy(boolean formalDecision) {
        String fields = EXAMPLE_FIELDS.stripTrailing();
        if (formalDecision) {
            fields += ",\n  \"classification\": {\"blocker\": 0, \"medium\": 1, \"low\": 0}";
        }
        return """


                ## Review Output Format (required)

                When exploration is complete, return exactly one bare JSON object with "summary" and
                "findings". Do not wrap it in Markdown fences or add prose or another JSON object.
                Do not include requestFiles, requestTools or runTools in the completed review.

                Completion example:
                {
                """ + fields + "\n}\n\n" + FINDING_RULES
                + (formalDecision
                ? "\nInclude \"classification\" inside this same completed object, with non-negative integer "
                  + "\"blocker\", \"medium\" and \"low\" counts matching the findings. "
                  + "Never append a separate classification object."
                : "\nClassification is optional; if included, nest it inside this same completed object.")
                + "\nWrite nothing outside the single bare JSON object.";
    }
}
