package com.cyx.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cyx.backend.dto.PendingAgentAction;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentConfirmationStoreTests {
    @Test
    void shouldConsumePendingActionBeforeExpiration() {
        AgentConfirmationStore store = new AgentConfirmationStore(Duration.ofMinutes(2));
        PendingAgentAction saved = store.save(1L, deleteAction(10L));

        PendingAgentAction consumed = store.consume(1L, saved.id());

        assertThat(consumed.action()).isEqualTo("DELETE");
        assertThat(consumed.eventId()).isEqualTo(10L);
        assertThat(consumed.expiresAt()).isNotNull();
    }

    @Test
    void shouldRejectExpiredPendingAction() {
        AgentConfirmationStore store = new AgentConfirmationStore(Duration.ZERO);
        PendingAgentAction saved = store.save(1L, deleteAction(10L));

        assertThatThrownBy(() -> store.consume(1L, saved.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已过期");
    }

    private PendingAgentAction deleteAction(Long eventId) {
        return new PendingAgentAction(
                null,
                null,
                "DELETE",
                eventId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
