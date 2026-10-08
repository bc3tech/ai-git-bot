package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrWorkflowContextTest {
    private PrWorkflowContext context(WebhookPayload payload, Map<String, String> hints) {
        return new PrWorkflowContext(new Bot(), payload, 1L, (name, log) -> {}, () -> false, hints);
    }
    @Test void commentCommandFlagIsExplicit() {
        assertThat(context(new WebhookPayload(), Map.of(PrWorkflowContext.HINT_EXPLICIT_REVIEW_REQUEST, "true"))
                .explicitRequest()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"review_requested"})
    void explicitEventCannotLoseFlag(String action) {
        WebhookPayload payload = new WebhookPayload(); payload.setAction(action);
        assertThat(context(payload, Map.of()).explicitRequest()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"opened", "synchronize", "synchronized", "reopened", "assigned"})
    void automaticEventsAreNotExplicit(String action) {
        WebhookPayload payload = new WebhookPayload(); payload.setAction(action);
        assertThat(context(payload, Map.of()).explicitRequest()).isFalse();
    }
}
