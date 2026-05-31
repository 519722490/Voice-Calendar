package com.cyx.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cyx.backend.dto.PendingAgentAction;
import com.cyx.backend.dto.PendingRecurringAgentAction;
import java.time.Duration;
import java.util.List;
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

    @Test
    void shouldConsumePendingRecurringActionBeforeExpiration() {
        AgentConfirmationStore store = new AgentConfirmationStore(Duration.ofMinutes(2));
        PendingRecurringAgentAction saved = store.saveRecurring(1L, recurringAction());

        AgentConfirmationStore.ConsumedPendingAction consumed = store.consumeAny(1L, saved.id());

        assertThat(consumed.singleAction()).isNull();
        assertThat(consumed.recurringAction()).isNotNull();
        assertThat(consumed.recurringAction().title()).isEqualTo("背单词");
        assertThat(consumed.recurringAction().expiresAt()).isNotNull();
    }

    @Test
    void shouldCancelPendingActionBeforeConfirmation() {
        AgentConfirmationStore store = new AgentConfirmationStore(Duration.ofMinutes(2));
        PendingAgentAction saved = store.save(1L, deleteAction(10L));

        store.cancelAny(1L, saved.id());

        assertThatThrownBy(() -> store.consumeAny(1L, saved.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已过期");
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

    private PendingRecurringAgentAction recurringAction() {
        return new PendingRecurringAgentAction(
                null,
                null,
                "CREATE_RECURRING",
                null,
                "背单词",
                "2030-06-01",
                "2030-06-03",
                "20:00",
                null,
                "DAILY",
                1,
                List.of(),
                null,
                null,
                "学习",
                null
        );
    }
}
