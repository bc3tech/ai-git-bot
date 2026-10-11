package org.remus.giteabot.agent.writerimpl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop's hard cap has to leave room for both rounds the writer's wrap-up
 * policy spends beyond the repository-context budget: the round that carries the
 * instruction and the round that produces the answer. A cap of
 * {@code maxToolRounds + 1} — the historic value — silently restores the bug where
 * a run that exhausted its budget never gets to answer.
 */
class WriterAgentServiceTest {

    @Test
    void loopHardCap_leavesARoundForTheWrapUpAndARoundForTheAnswer() {
        assertThat(WriterAgentService.loopMaxRounds(5)).isEqualTo(7);
    }

    @Test
    void loopHardCap_followsARaisedConfiguredBudget() {
        assertThat(WriterAgentService.loopMaxRounds(8)).isEqualTo(10);
    }
}
