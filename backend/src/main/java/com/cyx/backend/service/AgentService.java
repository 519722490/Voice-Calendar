package com.cyx.backend.service;

import com.cyx.backend.dto.AgentChatRequest;
import com.cyx.backend.dto.AgentChatResponse;
import com.cyx.backend.dto.AgentActionResult;
import com.cyx.backend.dto.CalendarAgentIntent;
import com.cyx.backend.dto.CalendarAgentPlan;
import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.dto.PendingAgentAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final String ACTION_BATCH = "BATCH";
    private static final int MAX_REVIEW_ACTIONS = 8;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final String NO_CLEAR_INTENT_MESSAGE = "未识别到明确的日程管理信息，请说明要添加、查看、修改或删除的日程。";
    private static final String UNCLEAR_TARGET_MESSAGE = "无法确定要操作的具体日程，请提供标题、日期或时间后重试。";

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
                    "已删除日程：\n" + formatEvent(existing),
                    MODE_REVIEW,
                    ACTION_DELETE,
                    null,
                    List.of()
            );
        }

        if (ACTION_UPDATE.equals(normalizedAction)) {
            CalendarEvent updated = updateEvent(userId, storedAction);
            return AgentChatResponse.done(
                    "已修改日程：\n" + formatEvent(updated),
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
            String normalizedContent = blankToNull(content);
            if (normalizedContent == null || NO_CLEAR_INTENT_MESSAGE.equals(normalizedContent)) {
                return AgentChatResponse.failed(NO_CLEAR_INTENT_MESSAGE, mode, ACTION_NONE, List.of());
            }
            if (isForbiddenAutoModeReply(normalizedContent)) {
                return AgentChatResponse.failed(UNCLEAR_TARGET_MESSAGE, mode, ACTION_NONE, List.of());
            }
            return AgentChatResponse.done(normalizedContent, mode, ACTION_NONE, null, List.of());
        } catch (Exception exception) {
            return AgentChatResponse.failed("AI 调用失败：" + exception.getMessage(), mode, ACTION_NONE, List.of());
        }
    }

    private AgentChatResponse chatWithReviewMode(AgentChatRequest request, String mode) {
        ChatClient chatClient = reviewChatClientProvider.getIfAvailable();
        if (!aiEnabled || chatClient == null) {
            return AgentChatResponse.disabled(mode);
        }

        CalendarAgentPlan plan;
        try {
            plan = parsePlan(chatClient, request.message());
        } catch (Exception exception) {
            return AgentChatResponse.failed("意图解析失败：" + exception.getMessage(), mode, ACTION_NONE, List.of());
        }

        try {
            return executeReviewedPlan(plan, mode);
        } catch (Exception exception) {
            return AgentChatResponse.failed("日程操作失败：" + exception.getMessage(), mode, ACTION_NONE, List.of());
        }
    }

    private CalendarAgentPlan parsePlan(ChatClient chatClient, String message) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(agentZoneId);
        String content = chatClient.prompt()
                .user("""
                        请把用户的语音文本解析成一个 JSON 计划对象。
                        如果用户一次说了多条日程管理指令，必须拆分成 actions 数组中的多条 action，不要合并成一条。

                        当前日期时间：%s
                        当前日期：%s
                        时区：%s

                        顶层 JSON 字段：
                        - summary：一句话总结本次计划。
                        - confidence：0 到 1 的整体置信度。
                        - actions：数组。每个元素是一条独立日程操作。

                        单条 action 字段说明：
                        - action：只能是 CREATE、QUERY、UPDATE、DELETE、NONE。
                        - title/date/startTime/endTime/location/description/tag/reminderTime：用于创建或查询；修改/删除时 date 表示原日程所在日期；date 必须是 yyyy-MM-dd，时间必须是 HH:mm。
                        - tag 和 newTag 只能从固定值中选择：会议、工作、学习、生活、运动、出行、提醒、其他。识别不出来时必须填写“其他”。
                        - targetId：用户明确说出日程 id 时填写。
                        - targetTitleKeyword：修改或删除时，用于定位原日程的标题关键词。
                        - targetStartTime：修改或删除时，用于定位原日程的原开始时间，格式 HH:mm。
                        - newTitle/newDate/newStartTime/newEndTime/newLocation/newDescription/newTag/newReminderTime：修改后的新字段。
                        - confidence：0 到 1 的置信度。
                        - reason：一句话说明解析理由。

                        规则：
                        1. 用户表达添加日程时用 CREATE；只要有日期、开始时间和日程内容即可创建。
                        2. “今天下午三点开会”的 title 是“开会”，date 用当前日期换算，startTime 是 15:00。
                        3. 创建日程时必须输出 tag。标签示例：开会、评审、讨论、汇报 => 会议；去工位、写代码、项目开发 => 工作；上课、复习、考试 => 学习；吃饭、购物、看病 => 生活；跑步、健身、打球 => 运动；出差、坐车、去机场 => 出行；提醒我、记得、闹钟 => 提醒。
                        4. 修改时，target 字段描述原日程，new 字段描述要改成的新内容。例如“把今天三点的会改到四点”：targetStartTime=15:00，newStartTime=16:00。
                        5. 删除时只提取定位条件，不要假装已经删除。
                        6. 完全没有日程管理含义时，action=NONE。
                        7. “然后、还有、再、另外、顺便、以及”通常表示多条指令。
                        8. 如果一句话中出现多个明确时间点和多个日程内容，通常应拆成多条 CREATE。
                        9. 如果某一条缺少必要字段，只让这一条保留缺失字段，不要影响其它条。
                        10. 最多输出 %d 条 action；如果超过，保留最明确的前 %d 条。
                        11. 必须只输出 JSON 对象，不要输出 Markdown、解释文字或代码块。

                        输出示例：
                        {
                          "summary": "用户想创建两条日程",
                          "confidence": 0.92,
                          "actions": [
                            {
                              "action": "CREATE",
                              "title": "开会",
                              "date": "%s",
                              "startTime": "15:00",
                              "tag": "会议",
                              "confidence": 0.95,
                              "reason": "用户说今天下午三点开会"
                            },
                            {
                              "action": "CREATE",
                              "title": "复习英语",
                              "date": "%s",
                              "startTime": "09:00",
                              "tag": "学习",
                              "confidence": 0.93,
                              "reason": "用户说明天上午九点复习英语"
                            }
                          ]
                        }

                        用户语音文本：%s
                        """.formatted(
                        now.toLocalDateTime(),
                        now.toLocalDate(),
                        agentZoneId,
                        MAX_REVIEW_ACTIONS,
                        MAX_REVIEW_ACTIONS,
                        now.toLocalDate(),
                        now.toLocalDate().plusDays(1),
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

    private AgentChatResponse executeReviewedPlan(CalendarAgentPlan plan, String mode) {
        List<CalendarAgentIntent> actions = plan.actions().stream()
                .filter(action -> action != null)
                .limit(MAX_REVIEW_ACTIONS)
                .toList();

        if (actions.isEmpty()) {
            return AgentChatResponse.failed(
                    NO_CLEAR_INTENT_MESSAGE,
                    mode,
                    ACTION_NONE,
                    List.of()
            );
        }

        Long userId = currentUserService.requireCurrentUserId();
        List<AgentActionResult> results = new ArrayList<>();

        for (int index = 0; index < actions.size(); index++) {
            CalendarAgentIntent intent = actions.get(index);
            results.add(executeReviewedAction(index + 1, intent, mode, userId));
        }

        return summarizeReviewedResults(mode, results);
    }

    private AgentActionResult executeReviewedAction(int index, CalendarAgentIntent intent, String mode, Long userId) {
        String action = normalizeAction(intent.action());

        try {
            AgentChatResponse response = switch (action) {
                case ACTION_CREATE -> createEvent(userId, intent, mode);
                case ACTION_QUERY -> queryEvents(userId, intent, mode);
                case ACTION_UPDATE -> prepareUpdate(userId, intent, mode);
                case ACTION_DELETE -> prepareDelete(userId, intent, mode);
                default -> AgentChatResponse.failed(
                        NO_CLEAR_INTENT_MESSAGE,
                        mode,
                        ACTION_NONE,
                        List.of()
                );
            };
            return AgentActionResult.fromResponse(index, response);
        } catch (Exception exception) {
            return new AgentActionResult(
                    index,
                    action,
                    false,
                    false,
                    "日程操作失败：" + exception.getMessage(),
                    null,
                    List.of(),
                    null
            );
        }
    }

    private AgentChatResponse summarizeReviewedResults(String mode, List<AgentActionResult> results) {
        String action = results.size() > 1 ? ACTION_BATCH : results.getFirst().action();
        boolean success = results.stream().allMatch(result -> result.success() || result.needsConfirmation());
        String content = formatReviewedSummary(results);

        return AgentChatResponse.batch(content, mode, action, success, results);
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
        return AgentChatResponse.done("已添加日程：\n" + formatEvent(event), mode, ACTION_CREATE, event, List.of(event));
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
        if (!hasAnyTargetLocator(intent)) {
            return AgentChatResponse.failed(UNCLEAR_TARGET_MESSAGE, mode, ACTION_UPDATE, List.of());
        }

        List<CalendarEvent> candidates = findCandidates(userId, intent);
        if (candidates.isEmpty()) {
            return AgentChatResponse.failed("没有找到符合条件的日程，无法修改。", mode, ACTION_UPDATE, List.of());
        }
        if (candidates.size() > 1) {
            return AgentChatResponse.failed(
                    UNCLEAR_TARGET_MESSAGE,
                    mode,
                    ACTION_UPDATE,
                    List.of()
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
                "将修改这个日程，请确认：\n" + formatEvent(target) + "\n修改内容：\n" + summarizePendingUpdate(pendingAction) + "\n确认操作会自动过期，请尽快确认。",
                ACTION_UPDATE,
                candidates,
                pendingAction
        );
    }

    private AgentChatResponse prepareDelete(Long userId, CalendarAgentIntent intent, String mode) {
        if (!hasAnyTargetLocator(intent)) {
            return AgentChatResponse.failed(UNCLEAR_TARGET_MESSAGE, mode, ACTION_DELETE, List.of());
        }

        List<CalendarEvent> candidates = findCandidates(userId, intent);
        if (candidates.isEmpty()) {
            return AgentChatResponse.failed("没有找到符合条件的日程，无法删除。", mode, ACTION_DELETE, List.of());
        }
        if (candidates.size() > 1) {
            return AgentChatResponse.failed(
                    UNCLEAR_TARGET_MESSAGE,
                    mode,
                    ACTION_DELETE,
                    List.of()
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

    private boolean hasAnyTargetLocator(CalendarAgentIntent intent) {
        return intent.targetId() != null
                || !isBlank(intent.date())
                || !isBlank(intent.targetTitleKeyword())
                || !isBlank(intent.targetStartTime())
                || !isBlank(intent.startTime());
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
            builder.append("\n");
        }
        builder.append(label).append("：").append(value.trim());
    }

    private String formatEvents(List<CalendarEvent> events) {
        int limit = Math.min(events.size(), 8);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            if (!builder.isEmpty()) {
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
        appendLine(builder, "标题", event.title());
        appendLine(builder, "时间", formatDateTimeRange(event.startTime(), event.endTime()));
        appendLine(builder, "地点", event.location());
        appendLine(builder, "标签", event.tag());
        appendLine(builder, "提醒", event.reminderTime() == null ? null : event.reminderTime().format(DATE_TIME_FORMATTER));
        appendLine(builder, "备注", event.description());
        return builder.toString();
    }

    private String formatEventSummary(CalendarEvent event) {
        StringBuilder builder = new StringBuilder();
        builder.append(event.title()).append(" | 时间：").append(formatDateTimeRange(event.startTime(), event.endTime()));
        if (!isBlank(event.location())) {
            builder.append(" | 地点：").append(event.location().trim());
        }
        if (!isBlank(event.tag())) {
            builder.append(" | 标签：").append(event.tag().trim());
        }
        if (event.reminderTime() != null) {
            builder.append(" | 提醒：").append(event.reminderTime().format(DATE_TIME_FORMATTER));
        }
        return builder.toString();
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

    private void appendLine(StringBuilder builder, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(label).append("：").append(value.trim());
    }

    private String formatReviewedSummary(List<AgentActionResult> results) {
        if (results.isEmpty()) {
            return NO_CLEAR_INTENT_MESSAGE;
        }
        if (results.size() == 1) {
            return results.getFirst().message();
        }

        long succeededCount = results.stream()
                .filter(result -> result.success() && !result.needsConfirmation())
                .count();
        long pendingCount = results.stream()
                .filter(AgentActionResult::needsConfirmation)
                .count();
        long failedCount = results.size() - succeededCount - pendingCount;

        return "已处理 " + results.size() + " 条日程指令：成功 " + succeededCount
                + " 条，待确认 " + pendingCount + " 条，失败 " + failedCount + " 条。";
    }

    private String normalizeMode(String mode) {
        return MODE_AUTO.equalsIgnoreCase(blankToNull(mode)) ? MODE_AUTO : MODE_REVIEW;
    }

    private boolean isForbiddenAutoModeReply(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return content.contains("是否确认")
                || content.contains("请确认")
                || content.contains("我看到多个")
                || content.contains("多个日程")
                || content.contains("最可能")
                || content.contains("最近创建")
                || content.contains("根据最近")
                || content.contains("刚刚的日程")
                || content.contains("候选")
                || content.contains("createdAt")
                || content.contains("updatedAt")
                || normalized.contains("createdat")
                || normalized.contains("updatedat")
                || normalized.contains("id=")
                || normalized.contains("id:")
                || content.contains("id：");
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
