package org.remus.giteabot.review.grounding;

import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.review.ReviewDocument;

public record FindingDisposition(ReviewDocument.Finding finding, AuditEventType type, String stage, String reason) {
}
