package org.remus.giteabot.review.grounding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.AiIntegrationService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiFlavor;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.review.ReviewFence;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.remus.giteabot.review.grounding.DoubleCheckService.*;

@Tag("live-eval")
class DoubleCheckLiveEvalTest {
    @Test
    void evaluatesRealDoubleCheckPromptAgainstConfiguredEndpoint() throws Exception {
        String endpoint = System.getenv("DOUBLE_CHECK_EVAL_ENDPOINT");
        String apiKey = System.getenv("DOUBLE_CHECK_EVAL_API_KEY");
        String model = System.getenv("DOUBLE_CHECK_EVAL_MODEL");
        assumeTrue(present(endpoint) && present(apiKey) && present(model),
                "Set DOUBLE_CHECK_EVAL_ENDPOINT, DOUBLE_CHECK_EVAL_API_KEY and DOUBLE_CHECK_EVAL_MODEL to run live evaluation");

        String flavor = System.getenv().getOrDefault("DOUBLE_CHECK_EVAL_MODEL_FLAVOR", "standard");
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30)).build());
        requests.setReadTimeout(Duration.ofMinutes(3));
        AiClient client = new OpenAiClient(RestClient.builder().baseUrl(endpoint).requestFactory(requests)
                .defaultHeader("Authorization", "Bearer " + apiKey).build(), model, 4096, false,
                OpenAiFlavor.fromId(flavor));
        DoubleCheckService service = new DoubleCheckService(mock(AiIntegrationService.class),
                mock(AiClientFactory.class), mock(PrAuditEventService.class));
        List<DoubleCheckEvaluation.Case> cases = DoubleCheckEvaluation.cases();
        List<DoubleCheckEvaluation.Result> results = new ArrayList<>();
        for (int repetition = 1; repetition <= DoubleCheckEvaluation.REPETITIONS; repetition++) {
            for (DoubleCheckEvaluation.Case testCase : cases) {
                Map<String, DoubleCheckOutcome> outcomes = service.check(List.of(testCase.candidate()),
                        testCase.evidence(), DoubleCheckSettings.defaults(), client,
                        new Budget(120_000, 8), null, ReviewFence.NONE);
                results.add(new DoubleCheckEvaluation.Result(testCase, repetition, outcomes.getOrDefault(testCase.id(),
                        new DoubleCheckOutcome(Verdict.FAILED, "Missing evaluation outcome"))));
            }
        }
        String report = "Double-check live evaluation: " + Instant.now() + "\nModel: " + model
                + "\nFlavor: " + flavor + "\nIntegration: same-model default (doubleCheckAiIntegrationId=0)\n\n"
                + DoubleCheckEvaluation.report(results);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "double-check-live-eval.txt"), report, StandardCharsets.UTF_8);
        System.out.println(report);
        DoubleCheckEvaluation.Metrics metrics = DoubleCheckEvaluation.metrics(results);
        assertThat(metrics.rejection()).as("False-finding rejection; see target/double-check-live-eval.txt")
                .isGreaterThanOrEqualTo(0.90);
        assertThat(metrics.retention()).as("Valid-finding retention; see target/double-check-live-eval.txt")
                .isGreaterThanOrEqualTo(0.95);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
