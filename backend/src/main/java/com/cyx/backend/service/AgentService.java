package com.cyx.backend.service;

import com.cyx.backend.dto.AgentChatRequest;
import com.cyx.backend.dto.AgentChatResponse;
import com.cyx.backend.dto.CalendarAgentIntent;
import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.dto.PendingAgentAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    private static final String MODE_AUTO = "auto";
    private static final String MODE_REVIEW = "review";
    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_QUERY = "QUERY";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_NONE = "NONE";

    private final ObjectProvider<ChatClient> autoChatClientProvider;
    private final ObjectProvider<ChatClient> reviewChatClientProvider;
    private final CalendarEventService eventService;
    private final CurrentUserService currentUserService;
    private final AgentConfirmationStore confirmationStore;
    private final ObjectMapper objectMapper;
    private final boolean aiEnabled;
    private final ZoneId agentZoneId;

    public AgentService(
            @Qualifier("voiceCalendarAutoChatClient") ObjectProvider<ChatClient> autoChatClientProvider,
            @Qualifier("voiceCalendarReviewChatClient") ObjectProvider<ChatClient> reviewChatClientProvider,
            CalendarEventService eventService,
            CurrentUserService currentUserService,
            AgentConfirmationStore confirmationStore,
            ObjectMapper objectMapper,
            @Value("${voice-calendar.ai.enabled:false}") boolean aiEnabled,
            @Value("${voice-calendar.agent.time-zone:Asia/Shanghai}") String agentTimeZone
    ) {
        this.autoChatClientProvider = autoChatClientProvider;
        this.reviewChatClientProvider = reviewChatClientProvider;
        this.eventService = eventService;
        this.currentUserService = currentUserService;
        this.confirmationStore = confirmationStore;
        this.objectMapper = objectMapper;
        this.aiEnabled = aiEnabled;
        this.agentZoneId = ZoneId.of(agentTimeZone);
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        String mode = normalizeMode(request.mode());
        if (MODE_AUTO.equals(mode)) {
            return chatWithAutoMode(request, mode);
        }
        return chatWithReviewMode(request, mode);
    }

    public AgentChatResponse confirm(PendingAgentAction action) {
        Long userId = currentUserService.requireCurrentUserId();
        PendingAgentAction storedAction;
        try {
            storedAction = confirmationStore.consume(userId, action == null ? null : action.id());
        } catch (IllegalArgumentException exception) {
            return AgentChatResponse.failed(exception.getMessage(), MODE_REVIEW, ACTION_NONE, List.of());
        }

        String normalizedAction = normalizeAction(storedAction.action());
        if (ACTION_DELETE.equals(normalizedAction)) {
            CalendarEvent existing = eventService.getEvent(userId, storedAction.eventId());
            eventService.deleteEvent(userId, storedAction.eventId());
            return AgentChatResponse.done(
                    "已删除日程：" + formatEvent(existing),
                    MODE_REVIEW,
                    ACTION_DELETE,
                    null,
                    List.of()
            );
        }

        if (ACTION_UPDATE.equals(normalizedAction)) {
            CalendarEvent updated = updateEvent(userId, storedAction);
            return AgentChatResponse.done(
                    "已修改日程：" + formatEvent(updated),
                    MODE_REVIEW,
                    ACTION_UPDATE,
                    updated,
                    List.of(updated)
            );
        }

        return AgentChatResponse.failed("该操作不需要确认，或暂不支持确认执行。", MODE_REVIEW, normalizedAction, List.of());
    }

    private AgentChatResponse chatWithAutoMode(AgentChatRequest request, String mode) {
        ChatClient chatClient = autoChatClientProvider.getIfAvailable();
        if (!aiEnabled || chatClient == null) {
            return AgentChatResponse.disabled(mode);
        }

        try {
            String content = chatClient.prompt()
                    .user(request.message())
                    .call()
                    .content();
            return AgentChatResponse.done(content, mode, ACTION_NONE, null, List.of());
        } catch (Exception exception) {
            return AgentChatResponse.failed("AI 调用失败：" + exception.getMessage(), mode, ACTION_NONE, List.of());
        }
    }

    private AgentChatResponse chatWithReviewMode(AgentChatRequest request, String mode) {
        ChatClient chatClient = reviewChatClientProvider.getIfAvailable();
        if (!aiEnabled || chatClient == null) {
            return AgentChatResponse.disabled(mode);
        }

        CalendarAgentIntent intent;
        try {
            intent = parseIntent(chatClient, request.message());
        } catch (Exception exception) {
            return AgentChatResponse.failed("意图解析失败：" + exception.getMessage(), mode, ACTION_NONE, List.of());
        }

        try {
            return executeReviewedIntent(intent, mode);
        } catch (Exception exception) {
            String action = intent == null ? ACTION_NONE : normalizeAction(intent.action());
            return AgentChatResponse.failed("日程操作失败：" + exception.getMessage(), mode, action, List.of());
        }
    }

    private CalendarAgentIntent parseIntent(ChatClient chatClient, String message) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(agentZoneId);
        String content = chatClient.prompt()
                .user("""
                        请把用户的语音文本解析成一个 JSON 对象。

                        当前日期时间：%s
                        当前日期：%s
                        时区：%s

                        JSON 字段说明：
                        - action：只能是 CREATE、QUERY、UPDATE、DELETE、NONE。
                        - title/date/startTime/endTime/location/description/tag/reminderTime：用于创建或查询；修改/删除时 date 表示原日程所在日期；date 必须是 yyyy-MM-dd，时间必须是 HH:mm。
                        - targetId：用户明确说出日程 id 时填写。
                        - targetTitleKeyword：修改或删除时，用于定位原日程的标题关键词。
                        - targetStartTime：修改或删除时，用于定位原日程的原开始时间，格式 HH:mm。
                        - newTitle/newDate/newStartTime/newEndTime/newLocation/newDescription/newTag/newReminderTime：修改后的新字段。
                        - confidence：0 到 1 的置信度。
                        - reason：一句话说明解析理由。

                        规则：
                        1. 用户表达添加日程时用 CREATE；只要有日期、开始时间和日程内容即可创建。
                        2. “今天下午三点开会”的 title 是“开会”，date 用当前日期换算，startTime 是 15:00。
                        3. 修改时，target 字段描述原日程，new 字段描述要改成的新内容。例如“把今天三点的会改到四点”：targetStartTime=15:00，newStartTime=16:00。
                        4. 删除时只提取定位条件，不要假装已经删除。
                        5. 完全没有日程管理含义时，action=NONE。
                        6. 只输出 JSON 对象，不要输出其它任何内容。

                        用户语音文本：%s
                        """.formatted(now.toLocalDateTime(), now.toLocalDate(), agentZoneId, message))
                .call()
                .content();
        return objectMapper.readValue(extractJsonObject(content), CalendarAgentIntent.class);
    }

    private AgentChatResponse executeReviewedIntent(CalendarAgentIntent intent, String mode) {
        String action = normalizeAction(intent.action());
        Long userId = currentUserService.requireCurrentUserId();

        return switch (action) {
            case ACTION_CREATE -> createEvent(userId, intent, mode);
            case ACTION_QUERY -> queryEvents(userId, intent, mode);
            case ACTION_UPDATE -> prepareUpdate(userId, intent, mode);
            case ACTION_DELETE -> prepareDelete(userId, intent, mode);
            default -> AgentChatResponse.failed(
                    "未识别到明确的日程管理信息，请说明要添加、查看、修改或删除的日程。",
                    mode,
                    ACTION_NONE,
                    List.of()
            );
        };
    }

    private AgentChatResponse createEvent(Long userId, CalendarAgentIntent intent, String mode) {
        if (isBlank(intent.title()) || isBlank(intent.date()) || isBlank(intent.startTime())) {
            return AgentChatResponse.failed("缺少创建日程所需的标题、日期或开始时间，请再说得具体一点。", mode, ACTION_CREATE, List.of());
        }

        CalendarEvent event = eventService.createEvent(userId, new EventRequest(
                intent.title().trim(),
                toDateTime(intent.date(), intent.startTime()),
                toOptionalDateTime(intent.date(), intent.endTime()),
                blankToNull(intent.location()),
                blankToNull(intent.description()),
                blankToNull(intent.tag()),
                toOptionalDateTime(intent.date(), intent.reminderTime())
        ));
        return AgentChatResponse.done("已添加日程：" + formatEvent(event), mode, ACTION_CREATE, event, List.of(event));
    }

    private AgentChatResponse queryEvents(Long userId, CalendarAgentIntent intent, String mode) {
        LocalDate date = isBlank(intent.date()) ? null : LocalDate.parse(intent.date());
        List<CalendarEvent> events = eventService.findEvents(userId, date);
        if (events.isEmpty()) {
            String message = date == null ? "当前没有日程。" : "这一天没有日程：" + date;
            return AgentChatResponse.done(message, mode, ACTION_QUERY, null, events);
        }

        String message = "找到 " + events.size() + " 个日程：\n" + formatEvents(events);
        return AgentChatResponse.done(message, mode, ACTION_QUERY, null, events);
    }

    private AgentChatResponse prepareUpdate(Long userId, CalendarAgentIntent intent, String mode) {
        if (!hasAnyUpdateField(intent)) {
            return AgentChatResponse.failed("没有识别到要修改成什么内容，请说出新的时间、标题或地点。", mode, ACTION_UPDATE, List.of());
        }

        List<CalendarEvent> candidates = findCandidates(userId, intent);
        if (candidates.isEmpty()) {
            return AgentChatResponse.failed("没有找到符合条件的日程，无法修改。", mode, ACTION_UPDATE, List.of());
        }
        if (candidates.size() > 1) {
            return AgentChatResponse.confirmation(
                    "找到多个可能要修改的日程，请说得更具体一点：\n" + formatEvents(candidates),
                    ACTION_UPDATE,
                    candidates,
                    null
            );
        }

        CalendarEvent target = candidates.getFirst();
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                ACTION_UPDATE,
                target.id(),
                blankToNull(intent.newTitle()),
                blankToNull(intent.newDate()),
                blankToNull(intent.newStartTime()),
                blankToNull(intent.newEndTime()),
                blankToNull(intent.newLocation()),
                blankToNull(intent.newDescription()),
                blankToNull(intent.newTag()),
                blankToNull(intent.newReminderTime())
        ));
        return AgentChatResponse.confirmation(
                "将修改这个日程，请确认：\n" + formatEvent(target) + "\n修改内容：" + summarizePendingUpdate(pendingAction) + "\n确认操作会自动过期，请尽快确认。",
                ACTION_UPDATE,
                candidates,
                pendingAction
        );
    }

    private AgentChatResponse prepareDelete(Long userId, CalendarAgentIntent intent, String mode) {
        List<CalendarEvent> candidates = findCandidates(userId, intent);
        if (candidates.isEmpty()) {
            return AgentChatResponse.failed("没有找到符合条件的日程，无法删除。", mode, ACTION_DELETE, List.of());
        }
        if (candidates.size() > 1) {
            return AgentChatResponse.confirmation(
                    "找到多个可能要删除的日程，请说得更具体一点：\n" + formatEvents(candidates),
                    ACTION_DELETE,
                    candidates,
                    null
            );
        }

        CalendarEvent target = candidates.getFirst();
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                ACTION_DELETE,
                target.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        return AgentChatResponse.confirmation(
                "将删除这个日程，请确认：\n" + formatEvent(target) + "\n确认操作会自动过期，请尽快确认。",
                ACTION_DELETE,
                candidates,
                pendingAction
        );
    }

    private CalendarEvent updateEvent(Long userId, PendingAgentAction action) {
        CalendarEvent existing = eventService.getEvent(userId, action.eventId());
        LocalDate resolvedDate = isBlank(action.date())
                ? existing.startTime().toLocalDate()
                : LocalDate.parse(action.date());
        LocalTime resolvedStartTime = isBlank(action.startTime())
                ? existing.startTime().toLocalTime()
                : LocalTime.parse(action.startTime());
        LocalDateTime resolvedEndTime = resolveOptionalTimeOnDate(
                resolvedDate,
                action.endTime(),
                existing.endTime()
        );
        LocalDateTime resolvedReminderTime = resolveOptionalTimeOnDate(
                resolvedDate,
                action.reminderTime(),
                existing.reminderTime()
        );

        return eventService.updateEvent(userId, existing.id(), new EventRequest(
                firstNonBlank(action.title(), existing.title()),
                LocalDateTime.of(resolvedDate, resolvedStartTime),
                resolvedEndTime,
                firstNullableNonBlank(action.location(), existing.location()),
                firstNullableNonBlank(action.description(), existing.description()),
                firstNullableNonBlank(action.tag(), existing.tag()),
                resolvedReminderTime
        ));
    }

    private List<CalendarEvent> findCandidates(Long userId, CalendarAgentIntent intent) {
        if (intent.targetId() != null) {
            try {
                return List.of(eventService.getEvent(userId, intent.targetId()));
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        LocalDate date = isBlank(intent.date()) ? null : LocalDate.parse(intent.date());
        List<CalendarEvent> events = eventService.findEvents(userId, date);
        String keyword = blankToNull(intent.targetTitleKeyword());
        String targetStartTime = firstNonBlankOrNull(intent.targetStartTime(), intent.startTime());

        return events.stream()
                .filter(event -> keyword == null || containsIgnoreCase(event.title(), keyword)
                        || containsIgnoreCase(event.location(), keyword)
                        || containsIgnoreCase(event.description(), keyword)
                        || containsIgnoreCase(event.tag(), keyword))
                .filter(event -> isBlank(targetStartTime) || event.startTime().toLocalTime().equals(LocalTime.parse(targetStartTime)))
                .toList();
    }

    private String extractJsonObject(String content) {
        if (isBlank(content)) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型未返回 JSON 对象：" + trimmed);
        }
        return trimmed.substring(start, end + 1);
    }

    private LocalDateTime toDateTime(String date, String time) {
        return LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time));
    }

    private LocalDateTime toOptionalDateTime(String date, String time) {
        return isBlank(time) ? null : toDateTime(date, time);
    }

    private LocalDateTime resolveOptionalTimeOnDate(LocalDate date, String newTime, LocalDateTime existing) {
        if (!isBlank(newTime)) {
            return LocalDateTime.of(date, LocalTime.parse(newTime));
        }
        return existing == null ? null : LocalDateTime.of(date, existing.toLocalTime());
    }

    private boolean hasAnyUpdateField(CalendarAgentIntent intent) {
        return !isBlank(intent.newTitle())
                || !isBlank(intent.newDate())
                || !isBlank(intent.newStartTime())
                || !isBlank(intent.newEndTime())
                || !isBlank(intent.newLocation())
                || !isBlank(intent.newDescription())
                || !isBlank(intent.newTag())
                || !isBlank(intent.newReminderTime());
    }

    private String summarizePendingUpdate(PendingAgentAction action) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", action.title());
        appendField(builder, "日期", action.date());
        appendField(builder, "开始时间", action.startTime());
        appendField(builder, "结束时间", action.endTime());
        appendField(builder, "地点", action.location());
        appendField(builder, "备注", action.description());
        appendField(builder, "标签", action.tag());
        appendField(builder, "提醒时间", action.reminderTime());
        return builder.isEmpty() ? "未识别到修改内容" : builder.toString();
    }

    private void appendField(StringBuilder builder, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("，");
        }
        builder.append(label).append("=").append(value.trim());
    }

    private String formatEvents(List<CalendarEvent> events) {
        return events.stream()
                .limit(8)
                .map(this::formatEvent)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String formatEvent(CalendarEvent event) {
        StringBuilder builder = new StringBuilder();
        builder.append("#").append(event.id()).append(" ");
        builder.append(event.title()).append(" ");
        builder.append(event.startTime().toLocalDate()).append(" ");
        builder.append(event.startTime().toLocalTime());
        if (event.endTime() != null) {
            builder.append("-").append(event.endTime().toLocalTime());
        }
        if (!isBlank(event.location())) {
            builder.append(" @").append(event.location());
        }
        return builder.toString();
    }

    private String normalizeMode(String mode) {
        return MODE_AUTO.equalsIgnoreCase(blankToNull(mode)) ? MODE_AUTO : MODE_REVIEW;
    }

    private String normalizeAction(String action) {
        String normalized = blankToNull(action);
        if (normalized == null) {
            return ACTION_NONE;
        }
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case ACTION_CREATE -> ACTION_CREATE;
            case ACTION_QUERY -> ACTION_QUERY;
            case ACTION_UPDATE -> ACTION_UPDATE;
            case ACTION_DELETE -> ACTION_DELETE;
            default -> ACTION_NONE;
        };
    }

    private String firstNonBlank(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String firstNullableNonBlank(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String firstNonBlankOrNull(String first, String second) {
        String normalized = blankToNull(first);
        return normalized == null ? blankToNull(second) : normalized;
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
