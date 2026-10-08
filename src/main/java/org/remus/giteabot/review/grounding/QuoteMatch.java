package org.remus.giteabot.review.grounding;

/** Result of deterministic source-quote verification. Span line numbers are one-based and inclusive. */
public record QuoteMatch(boolean matched, MatchedSpan matchedSpan, String contextHash,
                         FailureReason failureReason) {

    public QuoteMatch {
        if (matched && (matchedSpan == null || failureReason != null)) {
            throw new IllegalArgumentException("A successful quote match needs a span and no failure reason");
        }
        if (!matched && (matchedSpan != null || failureReason == null)) {
            throw new IllegalArgumentException("A failed quote match needs a failure reason and no span");
        }
    }

    static QuoteMatch success(MatchedSpan span, String contextHash) {
        return new QuoteMatch(true, span, contextHash, null);
    }

    static QuoteMatch failure(FailureReason reason) {
        return new QuoteMatch(false, null, null, reason);
    }

    public record MatchedSpan(int startLine, int endLine) {
        public MatchedSpan {
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("Invalid matched line span");
            }
        }
    }

    public enum FailureReason {
        QUOTE_MISSING,
        QUOTE_TOO_WEAK,
        QUOTE_NOT_FOUND,
        QUOTE_OUT_OF_WINDOW,
        QUOTE_AMBIGUOUS,
        QUOTE_MISATTRIBUTED,
        INVALID_ANCHOR
    }
}
