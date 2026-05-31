package com.cyx.backend.service;

import com.cyx.backend.dto.AssistantChatRequest;
import com.cyx.backend.dto.AssistantStreamEvent;
import com.cyx.backend.dto.CalendarAgentIntent;
import com.cyx.backend.dto.CalendarAgentPlan;
import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.dto.PendingAgentAction;
import com.cyx.backend.dto.PendingRecurringAgentAction;
import com.cyx.backend.dto.RecurringEventRequest;
import com.cyx.backend.dto.RecurringEventResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AssistantService {
    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_QUERY = "QUERY";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_CREATE_RECURRING = "CREATE_RECURRING";
    private static final String ACTION_DELETE_RECURRING = "DELETE_RECURRING";
    private static final String ACTION_NONE = "NONE";
    private static final String SOURCE_TYPE_RECURRING = "RECURRING";
    private static final int MAX_ASSISTANT_ACTIONS = 5;
    private static final int MAX_MEMORY_EVENTS = 8;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final String UPDATE_UNSUPPORTED_MESSAGE = "当前 AI 助手暂不支持修改日程，请在日历页面手动编辑，或删除后重新创建。";

    private final ObjectProvider<ChatClient> assistantChatClientProvider;
    private final ObjectProvider<ChatClient> assistantPlanClientProvider;
    private final CalendarEventService eventService;
    private final RecurringEventService recurringEventService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final boolean aiEnabled;
    private final ZoneId assistantZoneId;
    private final double confidenceThreshold;
    private final Map<String, AssistantConversation> conversations = new ConcurrentHashMap<>();

    public AssistantService(
            @Qualifier("voiceCalendarAssistantChatClient") ObjectProvider<ChatClient> assistantChatClientProvider,
            @Qualifier("voiceCalendarAssistantPlanClient") ObjectProvider<ChatClient> assistantPlanClientProvider,
            CalendarEventService eventService,
            RecurringEventService recurringEventService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            @Value("${voice-calendar.ai.enabled:false}") boolean aiEnabled,
            @Value("${voice-calendar.assistant.time-zone:${voice-calendar.agent.time-zone:Asia/Shanghai}}") String assistantTimeZone,
            @Value("${voice-calendar.assistant.confidence-threshold:0.30}") double confidenceThreshold
    ) {
        this.assistantChatClientProvider = assistantChatClientProvider;
        this.assistantPlanClientProvider = assistantPlanClientProvider;
        this.eventService = eventService;
        this.recurringEventService = recurringEventService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.aiEnabled = aiEnabled;
        this.assistantZoneId = ZoneId.of(assistantTimeZone);
        this.confidenceThreshold = confidenceThreshold;
    }

    public SseEmitter chatStream(AssistantChatRequest request) {
        Long userId = currentUserService.requireCurrentUserId();
        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                AssistantReply reply = chat(userId, request);
                streamText(emitter, reply.content());
                send(emitter, AssistantStreamEvent.done(reply.refreshEvents()));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    send(emitter, AssistantStreamEvent.error("AI 助手处理失败：" + exception.getMessage()));
                } catch (IOException ignored) {
                    // The client may have already closed the stream.
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    private AssistantReply chat(Long userId, AssistantChatRequest request) throws Exception {
        String message = request.message().trim();
        AssistantConversation conversation = conversation(userId, request.conversationId());
        conversation.addMessage("user", message);

        AssistantDecision decision = parseAssistantDecision(message);
        if (conversation.pendingBatch() != null) {
            if (decision == AssistantDecision.CONFIRM) {
                AssistantReply reply = executePendingBatch(userId, conversation);
                conversation.addMessage("assistant", reply.content());
                return reply;
            }
            if (decision == AssistantDecision.CANCEL) {
                conversation.pendingBatch(null);
                AssistantReply reply = new AssistantReply("好的，已取消这次操作。", false);
                conversation.addMessage("assistant", reply.content());
                return reply;
            }
            AssistantReply reply = new AssistantReply("这次操作还在等待确认。请回复“确认执行”或“取消”。", false);
            conversation.addMessage("assistant", reply.content());
            return reply;
        }

        ChatClient planClient = assistantPlanClientProvider.getIfAvailable();
        if (!aiEnabled || planClient == null) {
            AssistantReply reply = new AssistantReply("AI 助手未启用。请配置 DashScope Key 并开启 voice-calendar.ai.enabled 后再使用。", false);
            conversation.addMessage("assistant", reply.content());
            return reply;
        }

        CalendarAgentPlan plan = parsePlan(planClient, message, conversation);
        List<CalendarAgentIntent> actions = plan.actions().stream()
                .filter(action -> action != null)
                .limit(MAX_ASSISTANT_ACTIONS)
                .toList();
        if (actions.isEmpty() || actions.stream().allMatch(action -> ACTION_NONE.equals(normalizeAction(action.action())))) {
            AssistantReply reply = conversationalReply(message, conversation);
            conversation.addMessage("assistant", reply.content());
            return reply;
        }

        List<String> immediateMessages = new ArrayList<>();
        List<AssistantPendingOperation> pendingOperations = new ArrayList<>();
        boolean refreshEvents = false;

        for (CalendarAgentIntent action : actions) {
            String normalizedAction = normalizeAction(action.action());
            if (isBelowConfidence(action)) {
                immediateMessages.add("我不太确定这条指令的日程信息，请再说得具体一点。");
                continue;
            }

            if (ACTION_QUERY.equals(normalizedAction)) {
                AssistantReply queryReply = executeQuery(userId, message, action, conversation);
                immediateMessages.add(queryReply.content());
                continue;
            }

            try {
                pendingOperations.add(preparePendingOperation(userId, message, action, conversation));
            } catch (IllegalArgumentException exception) {
                immediateMessages.add(exception.getMessage());
            }
        }

        if (!pendingOperations.isEmpty()) {
            AssistantPendingBatch batch = new AssistantPendingBatch(pendingOperations);
            conversation.pendingBatch(batch);
            immediateMessages.add(formatPendingBatch(batch));
        }

        if (pendingOperations.isEmpty()) {
            refreshEvents = immediateMessages.stream().anyMatch(messageItem -> messageItem.startsWith("已"));
        }

        String content = String.join("\n\n", immediateMessages);
        if (content.isBlank()) {
            content = "我没有识别到可以执行的日程操作。";
        }
        AssistantReply reply = new AssistantReply(content, refreshEvents);
        conversation.addMessage("assistant", reply.content());
        return reply;
    }

    private CalendarAgentPlan parsePlan(ChatClient chatClient, String message, AssistantConversation conversation) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(assistantZoneId);
        String content = chatClient.prompt()
                .user("""
                        请把用户输入解析成 Voice Calendar 助手的 JSON 计划对象。

                        当前日期时间：%s
                        当前日期：%s
                        时区：%s

                        会话记忆：
                        %s

                        顶层 JSON 字段：
                        - summary：一句话总结。
                        - confidence：0 到 1。
                        - actions：数组，最多 %d 条。

                        单条 action 字段：
                        - action：CREATE、QUERY、UPDATE、DELETE、NONE。
                        - title/date/startTime/endTime/location/description/tag/reminderTime：创建或查询用；date=yyyy-MM-dd，time=HH:mm。
                        - targetTitleKeyword/targetStartTime/targetStartTimeFrom/targetStartTimeTo：删除时定位原日程。
                        - newTitle/newDate/newStartTime/newEndTime/newLocation/newDescription/newTag/newReminderTime：保留字段；当前 AI 助手不执行修改，解析到修改意图时可以留空。
                        - recurring：用户说每天、每周、工作日、本周每天、今年每天、每周一三五等周期表达时为 true。
                        - recurrenceType：DAILY、WEEKLY、MONTHLY。第一版优先 DAILY/WEEKLY。
                        - recurrenceStartDate/recurrenceEndDate/recurrenceInterval/recurrenceDaysOfWeek：重复规则字段。
                        - confidence：0 到 1。
                        - reason：一句话理由。

                        解析规则：
                        1. 查询日程直接 QUERY；“今天有什么安排” date=当前日期。
                        2. 添加/安排/提醒我/记一下 => CREATE。只要有日期、开始时间和日程内容即可。
                        3. 删除/取消/撤销/删掉/不去了/不用/不 + 日程内容 + 了 => DELETE。
                        4. 修改/改到/挪到/改成/提前/推迟 => UPDATE。当前 AI 助手不支持修改单次日程或重复日程，UPDATE 只用于返回不支持提示，不要把修改意图改写成 CREATE 或 DELETE。
                        5. “刚刚设置的、刚才那个、上一个、它、那个、最近的”是引用会话记忆，不要编造目标字段；action 正常解析，缺失定位字段允许留空，后端会用记忆补全。
                        6. 添加和删除不要假装已执行，只解析意图。
                        7. tag 只能是：会议、工作、学习、生活、运动、出行、提醒、其他。识别不出来用“其他”。
                        8. 完全没有日程管理含义时 action=NONE。
                        9. 必须只输出 JSON 对象。

                        用户输入：%s
                        """.formatted(
                        now.toLocalDateTime(),
                        now.toLocalDate(),
                        assistantZoneId,
                        conversation.memoryText(),
                        MAX_ASSISTANT_ACTIONS,
                        message
                ))
                .call()
                .content();
        return readPlan(content);
    }

    private CalendarAgentPlan readPlan(String content) throws Exception {
        String json = extractJsonObject(content);
        JsonNode root = objectMapper.readTree(json);
        if (root.has("actions") && root.path("actions").isArray()) {
            return objectMapper.readValue(json, CalendarAgentPlan.class);
        }

        CalendarAgentIntent singleIntent = objectMapper.readValue(json, CalendarAgentIntent.class);
        return new CalendarAgentPlan(List.of(singleIntent), "单条日程指令", singleIntent.confidence());
    }

    private AssistantReply conversationalReply(String message, AssistantConversation conversation) {
        ChatClient chatClient = assistantChatClientProvider.getIfAvailable();
        if (!aiEnabled || chatClient == null) {
            return new AssistantReply("我可以帮你查询、添加和删除日程。", false);
        }
        String content = chatClient.prompt()
                .user("""
                        请作为日历助手回复用户。
                        会话记忆：
                        %s

                        用户：%s
                        """.formatted(conversation.memoryText(), message))
                .call()
                .content();
        return new AssistantReply(isBlank(content) ? "我在，可以继续告诉我你的日程安排。" : content.trim(), false);
    }

    private AssistantReply executeQuery(Long userId, String message, CalendarAgentIntent intent, AssistantConversation conversation) {
        LocalDate date = resolveQueryDate(message, intent);
        List<CalendarEvent> events = date == null
                ? eventService.findEvents(userId, (LocalDate) null)
                : eventService.findEvents(userId, date);
        conversation.recentEvents(events);
        if (events.size() == 1) {
            conversation.lastEvent(events.getFirst());
        }

        if (events.isEmpty()) {
            String range = date == null ? "目前" : date.toString();
            return new AssistantReply(range + "没有查询到日程。", false);
        }

        String title = date == null ? "查询到这些日程：" : date + " 的日程：";
        return new AssistantReply(title + "\n" + formatEvents(events), false);
    }

    private AssistantPendingOperation preparePendingOperation(
            Long userId,
            String message,
            CalendarAgentIntent intent,
            AssistantConversation conversation
    ) {
        String action = normalizeAction(intent.action());
        if (ACTION_CREATE.equals(action)) {
            if (isRecurringIntent(intent)) {
                return prepareRecurringCreate(intent);
            }
            requireFields(intent.title(), intent.date(), intent.startTime(), "缺少创建日程所需的标题、日期或开始时间，请再说得具体一点。");
            PendingAgentAction pending = new PendingAgentAction(
                    null,
                    null,
                    ACTION_CREATE,
                    null,
                    intent.title().trim(),
                    intent.date(),
                    intent.startTime(),
                    blankToNull(intent.endTime()),
                    blankToNull(intent.location()),
                    blankToNull(intent.description()),
                    blankToNull(intent.tag()),
                    blankToNull(intent.reminderTime())
            );
            return new AssistantPendingOperation(ACTION_CREATE, pending, null, "添加日程：\n" + formatPendingEvent(pending));
        }

        if (ACTION_UPDATE.equals(action)) {
            throw new IllegalArgumentException(UPDATE_UNSUPPORTED_MESSAGE);
        }

        if (ACTION_DELETE.equals(action)) {
            if (isRecurringIntent(intent)) {
                return prepareRecurringDelete(userId, message, intent, conversation);
            }
            CalendarEvent target = resolveSingleTarget(userId, message, intent, conversation);
            PendingAgentAction pending = new PendingAgentAction(
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
            );
            return new AssistantPendingOperation(ACTION_DELETE, pending, null, "删除日程：\n" + formatEvent(target));
        }

        throw new IllegalArgumentException("这条指令暂不支持执行。");
    }

    private AssistantPendingOperation prepareRecurringCreate(CalendarAgentIntent intent) {
        requireFields(intent.title(), intent.startTime(), null, "缺少创建重复日程所需的标题或开始时间，请再说得具体一点。");
        String startDate = firstNonBlank(intent.recurrenceStartDate(), intent.date());
        if (isBlank(startDate)) {
            startDate = LocalDate.now(assistantZoneId).toString();
        }
        String endDate = blankToNull(intent.recurrenceEndDate());
        if (endDate == null) {
            endDate = LocalDate.parse(startDate).plusDays(29).toString();
        }

        PendingRecurringAgentAction pending = new PendingRecurringAgentAction(
                null,
                null,
                ACTION_CREATE_RECURRING,
                null,
                intent.title().trim(),
                startDate,
                endDate,
                intent.startTime(),
                blankToNull(intent.endTime()),
                firstNonBlank(intent.recurrenceType(), "DAILY"),
                intent.recurrenceInterval() == null ? 1 : intent.recurrenceInterval(),
                normalizeList(intent.recurrenceDaysOfWeek()),
                blankToNull(intent.location()),
                blankToNull(intent.description()),
                blankToNull(intent.tag()),
                blankToNull(intent.reminderTime())
        );
        return new AssistantPendingOperation(ACTION_CREATE_RECURRING, null, pending, "添加重复日程：\n" + formatPendingRecurringEvent(pending));
    }

    private AssistantPendingOperation prepareRecurringDelete(
            Long userId,
            String message,
            CalendarAgentIntent intent,
            AssistantConversation conversation
    ) {
        RecurringEventResponse target = resolveRecurringTarget(userId, message, intent, conversation);
        PendingRecurringAgentAction pending = new PendingRecurringAgentAction(
                null,
                null,
                ACTION_DELETE_RECURRING,
                target.id(),
                target.title(),
                target.startDate().toString(),
                target.endDate().toString(),
                target.startTime().toString(),
                target.endTime() == null ? null : target.endTime().toString(),
                target.recurrenceType(),
                target.intervalValue(),
                parseDaysList(target.daysOfWeek()),
                target.location(),
                target.description(),
                target.tag(),
                target.reminderTime() == null ? null : target.reminderTime().toString()
        );
        return new AssistantPendingOperation(ACTION_DELETE_RECURRING, null, pending, "删除整条重复日程规则：\n" + formatRecurringEvent(target));
    }

    private AssistantReply executePendingBatch(Long userId, AssistantConversation conversation) {
        AssistantPendingBatch batch = conversation.pendingBatch();
        conversation.pendingBatch(null);
        if (batch == null || batch.operations().isEmpty()) {
            return new AssistantReply("当前没有等待确认的操作。", false);
        }

        List<String> messages = new ArrayList<>();
        boolean refreshEvents = false;
        for (AssistantPendingOperation operation : batch.operations()) {
            try {
                AssistantExecutionResult result = executePendingOperation(userId, operation, conversation);
                messages.add(result.message());
                refreshEvents = refreshEvents || result.refreshEvents();
            } catch (Exception exception) {
                messages.add("执行失败：" + exception.getMessage());
            }
        }
        return new AssistantReply(String.join("\n\n", messages), refreshEvents);
    }

    private AssistantExecutionResult executePendingOperation(
            Long userId,
            AssistantPendingOperation operation,
            AssistantConversation conversation
    ) {
        if (operation.singleAction() != null) {
            PendingAgentAction action = operation.singleAction();
            return switch (operation.action()) {
                case ACTION_CREATE -> {
                    CalendarEvent event = eventService.createEvent(userId, toEventRequest(action));
                    conversation.lastEvent(event);
                    yield new AssistantExecutionResult("已添加日程：\n" + formatEvent(event), true);
                }
                case ACTION_UPDATE -> {
                    yield new AssistantExecutionResult(UPDATE_UNSUPPORTED_MESSAGE, false);
                }
                case ACTION_DELETE -> {
                    CalendarEvent existing = eventService.getEvent(userId, action.eventId());
                    eventService.deleteEvent(userId, action.eventId());
                    conversation.lastEvent(null);
                    yield new AssistantExecutionResult("已删除日程：\n" + formatEvent(existing), true);
                }
                default -> new AssistantExecutionResult("该操作暂不支持执行。", false);
            };
        }

        PendingRecurringAgentAction recurringAction = operation.recurringAction();
        if (recurringAction == null) {
            return new AssistantExecutionResult("该操作暂不支持执行。", false);
        }
        if (ACTION_CREATE_RECURRING.equals(operation.action())) {
            RecurringEventResponse event = recurringEventService.createEvent(userId, toRecurringRequest(recurringAction));
            conversation.lastRecurringEvent(event);
            return new AssistantExecutionResult("已添加重复日程：\n" + formatRecurringEvent(event), true);
        }
        if (ACTION_DELETE_RECURRING.equals(operation.action())) {
            RecurringEventResponse existing = recurringEventService.getEvent(userId, recurringAction.recurringEventId());
            recurringEventService.deleteEvent(userId, recurringAction.recurringEventId());
            conversation.lastRecurringEvent(null);
            return new AssistantExecutionResult("已删除重复日程：\n" + formatRecurringEvent(existing), true);
        }
        return new AssistantExecutionResult("该重复日程操作暂不支持执行。", false);
    }

    private EventRequest toEventRequest(PendingAgentAction action) {
        LocalDate date = LocalDate.parse(action.date());
        return new EventRequest(
                action.title(),
                LocalDateTime.of(date, LocalTime.parse(action.startTime())),
                isBlank(action.endTime()) ? null : LocalDateTime.of(date, LocalTime.parse(action.endTime())),
                action.location(),
                action.description(),
                action.tag(),
                isBlank(action.reminderTime()) ? null : LocalDateTime.of(date, LocalTime.parse(action.reminderTime()))
        );
    }

    private RecurringEventRequest toRecurringRequest(PendingRecurringAgentAction action) {
        return new RecurringEventRequest(
                action.title(),
                LocalDate.parse(action.startDate()),
                LocalDate.parse(action.endDate()),
                LocalTime.parse(action.startTime()),
                isBlank(action.endTime()) ? null : LocalTime.parse(action.endTime()),
                action.recurrenceType(),
                action.intervalValue(),
                action.daysOfWeek().isEmpty() ? null : String.join(",", action.daysOfWeek()),
                action.location(),
                action.description(),
                action.tag(),
                isBlank(action.reminderTime()) ? null : LocalTime.parse(action.reminderTime())
        );
    }

    private CalendarEvent resolveSingleTarget(
            Long userId,
            String message,
            CalendarAgentIntent intent,
            AssistantConversation conversation
    ) {
        if (hasMemoryReference(message) && conversation.lastEvent() != null && conversation.lastEvent().id() != null) {
            return conversation.lastEvent();
        }
        if (hasMemoryReference(message) && conversation.recentEvents().size() == 1 && conversation.recentEvents().getFirst().id() != null) {
            return conversation.recentEvents().getFirst();
        }
        if (intent.targetId() != null) {
            return eventService.getEvent(userId, intent.targetId());
        }

        List<CalendarEvent> candidates = isBlank(intent.date())
                ? eventService.findEvents(userId, (LocalDate) null)
                : eventService.findEvents(userId, LocalDate.parse(intent.date()));
        String keyword = firstNonBlank(intent.targetTitleKeyword(), intent.title());
        String targetStartTime = firstNonBlank(intent.targetStartTime(), intent.startTime());

        List<CalendarEvent> matched = candidates.stream()
                .filter(event -> !isRecurringEventInstance(event))
                .filter(event -> matchesKeyword(event, keyword))
                .filter(event -> matchesStartTime(event, targetStartTime))
                .filter(event -> matchesStartTimeRange(event, intent.targetStartTimeFrom(), intent.targetStartTimeTo()))
                .sorted(Comparator.comparing(CalendarEvent::startTime))
                .toList();

        if (matched.size() == 1) {
            return matched.getFirst();
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("没有找到要操作的日程，请补充标题、日期或时间。");
        }
        throw new IllegalArgumentException("找到多个可能的日程，请补充更明确的标题、日期或时间。");
    }

    private RecurringEventResponse resolveRecurringTarget(
            Long userId,
            String message,
            CalendarAgentIntent intent,
            AssistantConversation conversation
    ) {
        if (hasMemoryReference(message) && conversation.lastRecurringEvent() != null) {
            return conversation.lastRecurringEvent();
        }
        List<RecurringEventResponse> candidates = recurringEventService.findEvents(userId);
        String keyword = firstNonBlank(intent.targetTitleKeyword(), intent.title());
        String recurrenceType = blankToNull(intent.recurrenceType());
        String startTime = firstNonBlank(intent.targetStartTime(), intent.startTime());

        List<RecurringEventResponse> matched = candidates.stream()
                .filter(event -> isBlank(keyword) || containsIgnoreCase(event.title(), keyword) || containsIgnoreCase(event.tag(), keyword))
                .filter(event -> isBlank(recurrenceType) || recurrenceType.equalsIgnoreCase(event.recurrenceType()))
                .filter(event -> isBlank(startTime) || event.startTime().equals(LocalTime.parse(startTime)))
                .toList();
        if (matched.size() == 1) {
            return matched.getFirst();
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("没有找到要操作的重复日程，请补充标题、频率或时间。");
        }
        throw new IllegalArgumentException("找到多个可能的重复日程，请补充更明确的标题、频率或时间。");
    }

    private String formatPendingBatch(AssistantPendingBatch batch) {
        StringBuilder builder = new StringBuilder("我准备执行下面的操作，请回复“确认执行”或“取消”：");
        for (int index = 0; index < batch.operations().size(); index++) {
            builder.append("\n\n").append(index + 1).append(". ").append(batch.operations().get(index).summary());
        }
        return builder.toString();
    }

    private String formatEvents(List<CalendarEvent> events) {
        int limit = Math.min(events.size(), 8);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(index + 1).append(". ").append(formatEventSummary(events.get(index)));
        }
        if (events.size() > limit) {
            builder.append("\n还有 ").append(events.size() - limit).append(" 个日程未展示。");
        }
        return builder.toString();
    }

    private String formatEvent(CalendarEvent event) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", event.title());
        appendField(builder, "时间", formatDateTimeRange(event.startTime(), event.endTime()));
        appendField(builder, "地点", event.location());
        appendField(builder, "标签", event.tag());
        appendField(builder, "提醒", event.reminderTime() == null ? null : event.reminderTime().format(DATE_TIME_FORMATTER));
        appendField(builder, "备注", event.description());
        return builder.toString();
    }

    private String formatEventSummary(CalendarEvent event) {
        StringBuilder builder = new StringBuilder();
        builder.append(event.title()).append(" | ").append(formatDateTimeRange(event.startTime(), event.endTime()));
        if (!isBlank(event.location())) {
            builder.append(" | 地点：").append(event.location().trim());
        }
        if (!isBlank(event.tag())) {
            builder.append(" | 标签：").append(event.tag().trim());
        }
        return builder.toString();
    }

    private String formatPendingEvent(PendingAgentAction action) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", action.title());
        appendField(builder, "时间", action.date() + " " + action.startTime() + (isBlank(action.endTime()) ? "" : "-" + action.endTime()));
        appendField(builder, "地点", action.location());
        appendField(builder, "标签", action.tag());
        appendField(builder, "提醒", action.reminderTime());
        appendField(builder, "备注", action.description());
        return builder.toString();
    }

    private String formatPendingRecurringEvent(PendingRecurringAgentAction action) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", action.title());
        appendField(builder, "频率", formatRecurrence(action.recurrenceType(), action.intervalValue(), action.daysOfWeek()));
        appendField(builder, "范围", action.startDate() + " 至 " + action.endDate());
        appendField(builder, "时间", action.startTime() + (isBlank(action.endTime()) ? "" : "-" + action.endTime()));
        appendField(builder, "地点", action.location());
        appendField(builder, "标签", action.tag());
        appendField(builder, "提醒", action.reminderTime());
        appendField(builder, "备注", action.description());
        return builder.toString();
    }

    private String formatRecurringEvent(RecurringEventResponse event) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", event.title());
        appendField(builder, "频率", formatRecurrence(event.recurrenceType(), event.intervalValue(), parseDaysList(event.daysOfWeek())));
        appendField(builder, "范围", event.startDate() + " 至 " + event.endDate());
        appendField(builder, "时间", event.startTime() + (event.endTime() == null ? "" : "-" + event.endTime()));
        appendField(builder, "地点", event.location());
        appendField(builder, "标签", event.tag());
        appendField(builder, "提醒", event.reminderTime() == null ? null : event.reminderTime().toString());
        appendField(builder, "备注", event.description());
        return builder.toString();
    }

    private String formatRecurrence(String recurrenceType, Integer intervalValue, List<String> daysOfWeek) {
        int interval = intervalValue == null ? 1 : intervalValue;
        if ("WEEKLY".equalsIgnoreCase(recurrenceType)) {
            String days = daysOfWeek == null || daysOfWeek.isEmpty() ? "" : "（" + String.join("、", daysOfWeek) + "）";
            return interval == 1 ? "每周" + days : "每 " + interval + " 周" + days;
        }
        return interval == 1 ? "每天" : "每 " + interval + " 天";
    }

    private String formatDateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (endTime == null) {
            return startTime.format(DATE_TIME_FORMATTER);
        }
        if (startTime.toLocalDate().equals(endTime.toLocalDate())) {
            return startTime.format(DATE_TIME_FORMATTER) + "-" + endTime.format(TIME_FORMATTER);
        }
        return startTime.format(DATE_TIME_FORMATTER) + " 至 " + endTime.format(DATE_TIME_FORMATTER);
    }

    private void appendField(StringBuilder builder, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(label).append("：").append(value.trim());
    }

    private LocalDate resolveQueryDate(String message, CalendarAgentIntent intent) {
        if (!isBlank(intent.date())) {
            return LocalDate.parse(intent.date());
        }
        String normalized = message.trim();
        if (normalized.contains("全部") || normalized.contains("所有")) {
            return null;
        }
        return LocalDate.now(assistantZoneId);
    }

    private AssistantDecision parseAssistantDecision(String message) {
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(确认|执行|可以|好的|好|是的|对|没问题|yes|y).*")) {
            return AssistantDecision.CONFIRM;
        }
        if (normalized.matches(".*(取消|不要|算了|否|不用|先别|别执行|no|n).*")) {
            return AssistantDecision.CANCEL;
        }
        return AssistantDecision.NONE;
    }

    private boolean isBelowConfidence(CalendarAgentIntent intent) {
        return intent.confidence() != null && intent.confidence() < confidenceThreshold;
    }

    private boolean isRecurringIntent(CalendarAgentIntent intent) {
        return Boolean.TRUE.equals(intent.recurring())
                || !isBlank(intent.recurrenceType())
                || !isBlank(intent.recurrenceStartDate())
                || !isBlank(intent.recurrenceEndDate())
                || intent.recurrenceInterval() != null
                || (intent.recurrenceDaysOfWeek() != null && !intent.recurrenceDaysOfWeek().isEmpty());
    }

    private boolean hasMemoryReference(String message) {
        return message.contains("刚刚")
                || message.contains("刚才")
                || message.contains("上一个")
                || message.contains("最近")
                || message.contains("最新")
                || message.contains("它")
                || message.contains("那个");
    }

    private boolean matchesKeyword(CalendarEvent event, String keyword) {
        return isBlank(keyword)
                || containsIgnoreCase(event.title(), keyword)
                || containsIgnoreCase(event.location(), keyword)
                || containsIgnoreCase(event.description(), keyword)
                || containsIgnoreCase(event.tag(), keyword);
    }

    private boolean matchesStartTime(CalendarEvent event, String targetStartTime) {
        return isBlank(targetStartTime) || event.startTime().toLocalTime().equals(LocalTime.parse(targetStartTime));
    }

    private boolean matchesStartTimeRange(CalendarEvent event, String from, String to) {
        LocalTime start = event.startTime().toLocalTime();
        if (!isBlank(from) && start.isBefore(LocalTime.parse(from))) {
            return false;
        }
        return isBlank(to) || !start.isAfter(LocalTime.parse(to));
    }

    private boolean isRecurringEventInstance(CalendarEvent event) {
        return SOURCE_TYPE_RECURRING.equalsIgnoreCase(blankToNull(event.sourceType()))
                || (event.id() == null && event.recurringEventId() != null);
    }

    private AssistantConversation conversation(Long userId, String conversationId) {
        String key = userId + ":" + conversationId;
        return conversations.computeIfAbsent(key, ignored -> new AssistantConversation());
    }

    private void streamText(SseEmitter emitter, String content) throws IOException, InterruptedException {
        String normalized = content == null ? "" : content;
        if (normalized.isEmpty()) {
            return;
        }
        int chunkSize = 16;
        for (int index = 0; index < normalized.length(); index += chunkSize) {
            int end = Math.min(index + chunkSize, normalized.length());
            send(emitter, AssistantStreamEvent.delta(normalized.substring(index, end)));
            Thread.sleep(24L);
        }
    }

    private void send(SseEmitter emitter, AssistantStreamEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(event.type()).data(event));
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

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .toList();
    }

    private List<String> parseDaysList(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? List.of() : List.of(normalized.split(","));
    }

    private void requireFields(String first, String second, String third, String message) {
        if (isBlank(first) || isBlank(second) || (third != null && isBlank(third))) {
            throw new IllegalArgumentException(message);
        }
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

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && keyword != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum AssistantDecision {
        CONFIRM,
        CANCEL,
        NONE
    }

    private record AssistantReply(String content, boolean refreshEvents) {
    }

    private record AssistantExecutionResult(String message, boolean refreshEvents) {
    }

    private record AssistantPendingOperation(
            String action,
            PendingAgentAction singleAction,
            PendingRecurringAgentAction recurringAction,
            String summary
    ) {
    }

    private record AssistantPendingBatch(List<AssistantPendingOperation> operations) {
        private AssistantPendingBatch {
            operations = List.copyOf(operations);
        }
    }

    private static final class AssistantConversation {
        private final List<String> messages = new ArrayList<>();
        private List<CalendarEvent> recentEvents = List.of();
        private CalendarEvent lastEvent;
        private RecurringEventResponse lastRecurringEvent;
        private AssistantPendingBatch pendingBatch;

        synchronized void addMessage(String role, String content) {
            messages.add(role + "：" + content);
            while (messages.size() > 12) {
                messages.removeFirst();
            }
        }

        synchronized String memoryText() {
            StringBuilder builder = new StringBuilder();
            if (lastEvent != null) {
                builder.append("最近操作的单次日程：").append(lastEvent.title())
                        .append("，时间：").append(lastEvent.startTime().format(DATE_TIME_FORMATTER)).append("\n");
            }
            if (lastRecurringEvent != null) {
                builder.append("最近操作的重复日程：").append(lastRecurringEvent.title())
                        .append("，范围：").append(lastRecurringEvent.startDate()).append(" 至 ").append(lastRecurringEvent.endDate()).append("\n");
            }
            if (!recentEvents.isEmpty()) {
                builder.append("最近查询结果：");
                recentEvents.stream().limit(MAX_MEMORY_EVENTS).forEach(event ->
                        builder.append(event.title()).append("@").append(event.startTime().format(DATE_TIME_FORMATTER)).append("；")
                );
                builder.append("\n");
            }
            if (!messages.isEmpty()) {
                builder.append("最近对话：").append(String.join(" / ", messages));
            }
            return builder.length() == 0 ? "暂无" : builder.toString();
        }

        synchronized List<CalendarEvent> recentEvents() {
            return recentEvents;
        }

        synchronized void recentEvents(List<CalendarEvent> recentEvents) {
            this.recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        }

        synchronized CalendarEvent lastEvent() {
            return lastEvent;
        }

        synchronized void lastEvent(CalendarEvent lastEvent) {
            this.lastEvent = lastEvent;
        }

        synchronized RecurringEventResponse lastRecurringEvent() {
            return lastRecurringEvent;
        }

        synchronized void lastRecurringEvent(RecurringEventResponse lastRecurringEvent) {
            this.lastRecurringEvent = lastRecurringEvent;
        }

        synchronized AssistantPendingBatch pendingBatch() {
            return pendingBatch;
        }

        synchronized void pendingBatch(AssistantPendingBatch pendingBatch) {
            this.pendingBatch = pendingBatch;
        }
    }
}
