package com.cyx.backend.service;

import com.cyx.backend.dto.PendingAgentAction;
import com.cyx.backend.dto.PendingRecurringAgentAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentConfirmationStore {
    private final Map<String, StoredPendingAction> pendingActions = new ConcurrentHashMap<>();
    private final Map<String, StoredPendingRecurringAction> pendingRecurringActions = new ConcurrentHashMap<>();
    private final Duration confirmationTtl;

    public AgentConfirmationStore(
            @Value("${voice-calendar.agent.confirmation-ttl:PT2M}") Duration confirmationTtl
    ) {
        this.confirmationTtl = confirmationTtl;
    }

    public PendingAgentAction save(Long userId, PendingAgentAction action) {
        cleanupExpired();
        Instant expiresAt = Instant.now().plus(confirmationTtl);
        PendingAgentAction storedAction = new PendingAgentAction(
                UUID.randomUUID().toString(),
                expiresAt,
                action.action(),
                action.eventId(),
                action.title(),
                action.date(),
                action.startTime(),
                action.endTime(),
                action.location(),
                action.description(),
                action.tag(),
                action.reminderTime()
        );
        pendingActions.put(storedAction.id(), new StoredPendingAction(userId, storedAction));
        return storedAction;
    }

    public PendingRecurringAgentAction saveRecurring(Long userId, PendingRecurringAgentAction action) {
        cleanupExpired();
        Instant expiresAt = Instant.now().plus(confirmationTtl);
        PendingRecurringAgentAction storedAction = new PendingRecurringAgentAction(
                UUID.randomUUID().toString(),
                expiresAt,
                action.action(),
                action.recurringEventId(),
                action.title(),
                action.startDate(),
                action.endDate(),
                action.startTime(),
                action.endTime(),
                action.recurrenceType(),
                action.intervalValue(),
                action.daysOfWeek(),
                action.location(),
                action.description(),
                action.tag(),
                action.reminderTime()
        );
        pendingRecurringActions.put(storedAction.id(), new StoredPendingRecurringAction(userId, storedAction));
        return storedAction;
    }

    public PendingAgentAction consume(Long userId, String actionId) {
        cleanupExpired();
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("缺少待确认操作，请重新发起语音指令。");
        }

        StoredPendingAction stored = pendingActions.get(actionId);
        if (stored == null) {
            throw new IllegalArgumentException("待确认操作不存在或已过期，请重新发起语音指令。");
        }
        if (isExpired(stored.action())) {
            pendingActions.remove(actionId, stored);
            throw new IllegalArgumentException("待确认操作已过期，请重新发起语音指令。");
        }
        if (!Objects.equals(stored.userId(), userId)) {
            throw new IllegalArgumentException("不能确认其他用户的待执行操作。");
        }

        pendingActions.remove(actionId, stored);
        return stored.action();
    }

    public ConsumedPendingAction consumeAny(Long userId, String actionId) {
        cleanupExpired();
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("缺少待确认操作，请重新发起语音指令。");
        }

        StoredPendingAction stored = pendingActions.get(actionId);
        if (stored != null) {
            validateSingleAction(actionId, stored, userId);
            pendingActions.remove(actionId, stored);
            return new ConsumedPendingAction(stored.action(), null);
        }

        StoredPendingRecurringAction recurringStored = pendingRecurringActions.get(actionId);
        if (recurringStored != null) {
            validateRecurringAction(actionId, recurringStored, userId);
            pendingRecurringActions.remove(actionId, recurringStored);
            return new ConsumedPendingAction(null, recurringStored.action());
        }

        throw new IllegalArgumentException("待确认操作不存在或已过期，请重新发起语音指令。");
    }

    private void cleanupExpired() {
        pendingActions.entrySet().removeIf(entry -> isExpired(entry.getValue().action()));
        pendingRecurringActions.entrySet().removeIf(entry -> isExpired(entry.getValue().action()));
    }

    private boolean isExpired(PendingAgentAction action) {
        return action.expiresAt() == null || !Instant.now().isBefore(action.expiresAt());
    }

    private boolean isExpired(PendingRecurringAgentAction action) {
        return action.expiresAt() == null || !Instant.now().isBefore(action.expiresAt());
    }

    private void validateSingleAction(String actionId, StoredPendingAction stored, Long userId) {
        if (isExpired(stored.action())) {
            pendingActions.remove(actionId, stored);
            throw new IllegalArgumentException("待确认操作已过期，请重新发起语音指令。");
        }
        if (!Objects.equals(stored.userId(), userId)) {
            throw new IllegalArgumentException("不能确认其他用户的待执行操作。");
        }
    }

    private void validateRecurringAction(String actionId, StoredPendingRecurringAction stored, Long userId) {
        if (isExpired(stored.action())) {
            pendingRecurringActions.remove(actionId, stored);
            throw new IllegalArgumentException("待确认操作已过期，请重新发起语音指令。");
        }
        if (!Objects.equals(stored.userId(), userId)) {
            throw new IllegalArgumentException("不能确认其他用户的待执行操作。");
        }
    }

    private record StoredPendingAction(Long userId, PendingAgentAction action) {
    }

    private record StoredPendingRecurringAction(Long userId, PendingRecurringAgentAction action) {
    }

    public record ConsumedPendingAction(
            PendingAgentAction singleAction,
            PendingRecurringAgentAction recurringAction
    ) {
    }
}
