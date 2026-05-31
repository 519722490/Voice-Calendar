package com.cyx.backend.service;

import com.cyx.backend.dto.AgentChatRequest;
import com.cyx.backend.dto.AgentChatResponse;
import com.cyx.backend.dto.AgentActionResult;
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
    private static final String ACTION_CREATE_RECURRING = "CREATE_RECURRING";
    private static final String ACTION_DELETE_RECURRING = "DELETE_RECURRING";
    private static final String ACTION_NONE = "NONE";
    private static final String ACTION_BATCH = "BATCH";
    private static final String SOURCE_TYPE_RECURRING = "RECURRING";
    private static final int MAX_REVIEW_ACTIONS = 8;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> MEETING_KEYWORDS = List.of("会议", "开会", "例会", "讨论", "评审", "汇报", "碰头", "沟通");
    private static final String NO_CLEAR_INTENT_MESSAGE = "未识别到明确的日程管理信息，请说明要添加、查看、修改或删除的日程。";
    private static final String UNCLEAR_TARGET_MESSAGE = "无法确定要操作的具体日程，请提供标题、日期或时间后重试。";

    private final ObjectProvider<ChatClient> autoChatClientProvider;
    private final ObjectProvider<ChatClient> reviewChatClientProvider;
    private final CalendarEventService eventService;
    private final RecurringEventService recurringEventService;
    private final CurrentUserService currentUserService;
    private final AgentConfirmationStore confirmationStore;
    private final ObjectMapper objectMapper;
    private final boolean aiEnabled;
    private final ZoneId agentZoneId;
    private final double reviewConfidenceThreshold;
    private final double autoConfidenceThreshold;

    public AgentService(
            @Qualifier("voiceCalendarAutoChatClient") ObjectProvider<ChatClient> autoChatClientProvider,
            @Qualifier("voiceCalendarReviewChatClient") ObjectProvider<ChatClient> reviewChatClientProvider,
            CalendarEventService eventService,
            RecurringEventService recurringEventService,
            CurrentUserService currentUserService,
            AgentConfirmationStore confirmationStore,
            ObjectMapper objectMapper,
            @Value("${voice-calendar.ai.enabled:false}") boolean aiEnabled,
            @Value("${voice-calendar.agent.time-zone:Asia/Shanghai}") String agentTimeZone,
            @Value("${voice-calendar.agent.review-confidence-threshold:0.45}") double reviewConfidenceThreshold,
            @Value("${voice-calendar.agent.auto-confidence-threshold:0.8}") double autoConfidenceThreshold
    ) {
        this.autoChatClientProvider = autoChatClientProvider;
        this.reviewChatClientProvider = reviewChatClientProvider;
        this.eventService = eventService;
        this.recurringEventService = recurringEventService;
        this.currentUserService = currentUserService;
        this.confirmationStore = confirmationStore;
        this.objectMapper = objectMapper;
        this.aiEnabled = aiEnabled;
        this.agentZoneId = ZoneId.of(agentTimeZone);
        this.reviewConfidenceThreshold = reviewConfidenceThreshold;
        this.autoConfidenceThreshold = autoConfidenceThreshold;
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
        AgentConfirmationStore.ConsumedPendingAction consumedAction;
        try {
            consumedAction = confirmationStore.consumeAny(userId, action == null ? null : action.id());
        } catch (IllegalArgumentException exception) {
            return AgentChatResponse.failed(exception.getMessage(), MODE_REVIEW, ACTION_NONE, List.of());
        }

        if (consumedAction.recurringAction() != null) {
            PendingRecurringAgentAction recurringAction = consumedAction.recurringAction();
            String recurringActionType = blankToNull(recurringAction.action());
            if (ACTION_CREATE_RECURRING.equalsIgnoreCase(recurringActionType)) {
                RecurringEventResponse recurringEvent = createRecurringEvent(userId, recurringAction);
                return AgentChatResponse.done(
                        "已添加重复日程：\n" + formatRecurringEvent(recurringEvent),
                        MODE_REVIEW,
                        ACTION_CREATE_RECURRING,
                        null,
                        List.of()
                );
            }
            if (ACTION_DELETE_RECURRING.equalsIgnoreCase(recurringActionType)) {
                RecurringEventResponse existing = recurringEventService.getEvent(userId, recurringAction.recurringEventId());
                recurringEventService.deleteEvent(userId, recurringAction.recurringEventId());
                return AgentChatResponse.done(
                        "已删除重复日程：\n" + formatRecurringEvent(existing),
                        MODE_REVIEW,
                        ACTION_DELETE_RECURRING,
                        null,
                        List.of()
                );
            }
            return AgentChatResponse.failed("该重复日程操作暂不支持确认执行。", MODE_REVIEW, recurringAction.action(), List.of());
        }

        PendingAgentAction storedAction;
        storedAction = consumedAction.singleAction();

        String normalizedAction = normalizeAction(storedAction.action());
        if (ACTION_CREATE.equals(normalizedAction)) {
            CalendarEvent event = createEvent(userId, storedAction);
            return AgentChatResponse.done(
                    "已添加日程：\n" + formatEvent(event),
                    MODE_REVIEW,
                    ACTION_CREATE,
                    event,
                    List.of(event)
            );
        }

        if (ACTION_QUERY.equals(normalizedAction)) {
            return queryEvents(userId, storedAction.date(), MODE_REVIEW);
        }

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

    public AgentChatResponse cancel(PendingAgentAction action) {
        Long userId = currentUserService.requireCurrentUserId();
        try {
            confirmationStore.cancelAny(userId, action == null ? null : action.id());
            return AgentChatResponse.done("已取消执行。", MODE_REVIEW, ACTION_NONE, null, List.of());
        } catch (IllegalArgumentException exception) {
            return AgentChatResponse.failed(exception.getMessage(), MODE_REVIEW, ACTION_NONE, List.of());
        }
    }

    private AgentChatResponse chatWithAutoMode(AgentChatRequest request, String mode) {
        ChatClient chatClient = autoChatClientProvider.getIfAvailable();
        ChatClient reviewChatClient = reviewChatClientProvider.getIfAvailable();
        if (!aiEnabled || chatClient == null || reviewChatClient == null) {
            return AgentChatResponse.disabled(mode);
        }

        try {
            CalendarAgentPlan plan = parsePlan(reviewChatClient, request.message());
            AgentChatResponse gateResult = validateAutoPlan(plan, mode);
            if (gateResult != null) {
                return gateResult;
            }

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

    private AgentChatResponse validateAutoPlan(CalendarAgentPlan plan, String mode) {
        List<CalendarAgentIntent> actions = plan.actions().stream()
                .filter(action -> action != null)
                .limit(MAX_REVIEW_ACTIONS)
                .toList();
        if (actions.isEmpty() || actions.stream().noneMatch(action -> !ACTION_NONE.equals(normalizeAction(action.action())))) {
            return AgentChatResponse.failed(NO_CLEAR_INTENT_MESSAGE, mode, ACTION_NONE, List.of());
        }
        if (isBelowConfidenceThreshold(plan.confidence(), autoConfidenceThreshold)) {
            return AgentChatResponse.failed("识别置信度较低，自动模式已停止执行，请切换审查模式或把日程说得更明确。", mode, ACTION_NONE, List.of());
        }
        if (actions.stream().anyMatch(this::isRecurringIntent)) {
            return AgentChatResponse.failed("检测到周期日程。为避免误创建或误删除整条重复规则，请切换审查模式确认后执行。", mode, ACTION_NONE, List.of());
        }
        boolean hasLowConfidenceAction = actions.stream()
                .anyMatch(action -> isBelowConfidenceThreshold(effectiveConfidence(plan, action), autoConfidenceThreshold));
        if (hasLowConfidenceAction) {
            return AgentChatResponse.failed("识别置信度较低，自动模式已停止执行，请切换审查模式或把日程说得更明确。", mode, ACTION_NONE, List.of());
        }
        return null;
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
                        - action：只能是 CREATE、QUERY、UPDATE、DELETE、NONE。添加/安排/提醒我/记一下 => CREATE；查看/查询/有哪些 => QUERY；修改/改到/挪到/改成 => UPDATE；删除/取消/撤销/删掉/移除/不去了/不参加/作废/不再/不用/不 + 日程内容 + 了 => DELETE。
                        - title/date/startTime/endTime/location/description/tag/reminderTime：用于创建或查询；修改/删除时 date 表示原日程所在日期；date 必须是 yyyy-MM-dd，时间必须是 HH:mm。
                        - tag 和 newTag 只能从固定值中选择：会议、工作、学习、生活、运动、出行、提醒、其他。识别不出来时必须填写“其他”。
                        - targetId：用户明确说出日程 id 时填写。
                        - targetTitleKeyword：修改或删除时，用于定位原日程的标题关键词。
                        - targetStartTime：修改或删除时，用于定位原日程的原开始时间，格式 HH:mm。
                        - targetStartTimeFrom/targetStartTimeTo：修改或删除时，用于定位原日程开始时间范围，格式 HH:mm。上午=06:00 到 12:00，下午=12:00 到 18:00，晚上=18:00 到 23:59；没有时间段就留空。
                        - newTitle/newDate/newStartTime/newEndTime/newLocation/newDescription/newTag/newReminderTime：修改后的新字段。
                        - recurring：是否为重复日程。用户说每天、每周、每月、工作日、本周每天、今年每天、每周一三五等周期表达时必须为 true；无论是创建、查询、修改还是删除，只要目标是重复规则或一组重复发生的日程，都必须为 true。
                        - recurrenceType：重复类型，只能是 DAILY、WEEKLY、MONTHLY。第一版优先输出 DAILY 或 WEEKLY。
                        - recurrenceStartDate/recurrenceEndDate：重复日程的开始和结束日期，格式 yyyy-MM-dd。不要创建无限重复。
                        - recurrenceInterval：重复间隔，例如每天为 1，每两天为 2。
                        - recurrenceDaysOfWeek：每周重复的星期数组，例如 ["MON","WED","FRI"]；只有 WEEKLY 需要。
                        - confidence：0 到 1 的置信度。
                        - reason：一句话说明解析理由。

                        规则：
                        1. 用户表达添加日程时用 CREATE；只要有日期、开始时间和日程内容即可创建。
                        2. “今天下午三点开会”的 title 是“开会”，date 用当前日期换算，startTime 是 15:00。
                        3. 创建日程时必须输出 tag。标签示例：开会、评审、讨论、汇报 => 会议；去工位、写代码、项目开发 => 工作；上课、复习、考试 => 学习；吃饭、购物、看病 => 生活；跑步、健身、打球 => 运动；出差、坐车、去机场 => 出行；提醒我、记得、闹钟 => 提醒。
                        4. 周期表达必须输出 recurring=true，不要展开成多条普通 CREATE，也不要把周期删除/修改当成某一天的普通日程。例如“本周每天晚上八点背单词”是一条 DAILY 重复日程，不是 7 条普通日程。
                        5. “今年每天晚上八点背单词”应解析为：recurring=true，recurrenceType=DAILY，recurrenceStartDate=当前日期，recurrenceEndDate=当年 12-31，startTime=20:00。
                        6. “每周一三五晚上跑步”应解析为：recurring=true，recurrenceType=WEEKLY，recurrenceDaysOfWeek=["MON","WED","FRI"]。
                        7. 如果用户说“每天晚上背单词”但没有结束日期，recurrenceEndDate 可以留空，由审查模式后端默认未来 30 天；自动模式会拒绝执行。
                        8. 修改时，target 字段描述原日程，new 字段描述要改成的新内容。例如“把今天三点的会改到四点”：targetStartTime=15:00，newStartTime=16:00。
                        9. 删除时只提取定位条件，不要假装已经删除。不要只依赖固定关键词；用户表达不再执行某个已经安排的动作，或用否定句取消某个日程，本质是 DELETE。
                        10. 例如“取消今天下午三点的会议”“今天三点的会议不去了”“今天下午不背单词了”“明天不用上课了”都必须解析为 DELETE，不是 UPDATE，也不是 NONE。
                        11. “今天下午不背单词了”应解析为：action=DELETE，date=当前日期，targetTitleKeyword=背单词，targetStartTimeFrom=12:00，targetStartTimeTo=18:00。
                        12. “删除本周每天背单词”“取消每天背单词”“以后每周一三五不跑步了”应解析为：action=DELETE，recurring=true，targetTitleKeyword=背单词/跑步，并填写能识别出的 recurrenceType、recurrenceStartDate、recurrenceEndDate 或 recurrenceDaysOfWeek；绝不能解析成删除今天的单次日程。
                        13. 审查模式不使用对话记忆。“刚刚的日程、刚才那个、上一个、最近的、最新的、它、那个、这条、这个、刚添加的、刚创建的”等上下文指代都不能根据创建顺序、数据库最近记录或上一次操作推断目标。
                        14. 修改或删除中出现上下文指代时，只有同时存在明确日期、具体时间或时间段、可区分的标题关键词，才允许填写 target 字段；否则 action 仍可解析为 UPDATE/DELETE，但 targetId、targetTitleKeyword、targetStartTime、targetStartTimeFrom、targetStartTimeTo 必须留空，confidence 不高于 0.3。
                        15. 不要把“会议、日程、事情、安排、活动”这类泛化词单独当成上下文指代的强定位条件。例如“删除刚刚添加的会议”应解析为 DELETE 但不填写 targetTitleKeyword；“删除今天下午三点那个会议”可以填写 date、targetTitleKeyword=会议、targetStartTime=15:00。
                        16. 会议、会、开会、例会、讨论、评审、汇报属于会议类关键词；用户在没有上下文指代风险时说“会议/会”，targetTitleKeyword 可以填写“会议”。
                        17. 完全没有日程管理含义时，action=NONE。
                        18. “然后、还有、再、另外、顺便、以及”通常表示多条指令。
                        19. 如果一句话中出现多个明确时间点和多个日程内容，且没有周期语义，通常应拆成多条 CREATE。
                        20. 如果某一条缺少必要字段，只让这一条保留缺失字段，不要影响其它条。
                        21. 最多输出 %d 条 action；如果超过，保留最明确的前 %d 条。
                        22. 必须只输出 JSON 对象，不要输出 Markdown、解释文字或代码块。

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
        return applyRecurringKeywordGuard(message, readPlan(content));
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

    private CalendarAgentPlan applyRecurringKeywordGuard(String message, CalendarAgentPlan plan) {
        if (!containsRecurringExpression(message) || plan.actions().isEmpty()) {
            return plan;
        }

        boolean singleAction = plan.actions().size() == 1;
        List<CalendarAgentIntent> guardedActions = plan.actions().stream()
                .map(intent -> shouldForceRecurringIntent(message, intent, singleAction)
                        ? forceRecurringIntent(message, intent)
                        : intent)
                .toList();
        return new CalendarAgentPlan(guardedActions, plan.summary(), plan.confidence());
    }

    private boolean shouldForceRecurringIntent(String message, CalendarAgentIntent intent, boolean singleAction) {
        String action = normalizeAction(intent.action());
        if (ACTION_NONE.equals(action) || isRecurringIntent(intent)) {
            return false;
        }
        return singleAction
                || containsRecurringExpression(intent.title())
                || containsRecurringExpression(intent.targetTitleKeyword())
                || containsRecurringExpression(intent.reason());
    }

    private CalendarAgentIntent forceRecurringIntent(String message, CalendarAgentIntent intent) {
        return new CalendarAgentIntent(
                intent.action(),
                intent.title(),
                intent.date(),
                intent.startTime(),
                intent.endTime(),
                intent.location(),
                intent.description(),
                intent.tag(),
                intent.reminderTime(),
                intent.targetId(),
                intent.targetTitleKeyword(),
                intent.targetStartTime(),
                intent.targetStartTimeFrom(),
                intent.targetStartTimeTo(),
                intent.newTitle(),
                intent.newDate(),
                intent.newStartTime(),
                intent.newEndTime(),
                intent.newLocation(),
                intent.newDescription(),
                intent.newTag(),
                intent.newReminderTime(),
                true,
                firstNonBlankOrNull(intent.recurrenceType(), inferRecurrenceType(message)),
                intent.recurrenceStartDate(),
                intent.recurrenceEndDate(),
                intent.recurrenceInterval(),
                intent.recurrenceDaysOfWeek() == null || intent.recurrenceDaysOfWeek().isEmpty()
                        ? inferRecurrenceDaysOfWeek(message)
                        : intent.recurrenceDaysOfWeek(),
                intent.confidence(),
                intent.reason()
        );
    }

    private boolean containsRecurringExpression(String value) {
        if (isBlank(value)) {
            return false;
        }
        String normalized = value.trim();
        return normalized.contains("每天")
                || normalized.contains("每日")
                || normalized.contains("每晚")
                || normalized.contains("每早")
                || normalized.contains("每周")
                || normalized.contains("每星期")
                || normalized.contains("每礼拜")
                || normalized.contains("每月")
                || normalized.contains("工作日")
                || normalized.contains("本周每天")
                || normalized.contains("这周每天")
                || normalized.contains("今年每天")
                || normalized.contains("以后每天");
    }

    private String inferRecurrenceType(String value) {
        if (isBlank(value)) {
            return "DAILY";
        }
        if (value.contains("每月")) {
            return "MONTHLY";
        }
        if (value.contains("每周") || value.contains("每星期") || value.contains("每礼拜") || value.contains("工作日")) {
            return "WEEKLY";
        }
        return "DAILY";
    }

    private List<String> inferRecurrenceDaysOfWeek(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        if (value.contains("工作日")) {
            return List.of("MON", "TUE", "WED", "THU", "FRI");
        }
        List<String> days = new ArrayList<>();
        appendDayIfMentioned(days, value, "一", "MON");
        appendDayIfMentioned(days, value, "二", "TUE");
        appendDayIfMentioned(days, value, "三", "WED");
        appendDayIfMentioned(days, value, "四", "THU");
        appendDayIfMentioned(days, value, "五", "FRI");
        appendDayIfMentioned(days, value, "六", "SAT");
        if (value.contains("周日") || value.contains("周天") || value.contains("星期日") || value.contains("星期天") || value.contains("礼拜日") || value.contains("礼拜天")) {
            days.add("SUN");
        }
        return days;
    }

    private void appendDayIfMentioned(List<String> days, String value, String chineseDay, String normalizedDay) {
        if (value.contains("周" + chineseDay)
                || value.contains("星期" + chineseDay)
                || value.contains("礼拜" + chineseDay)) {
            days.add(normalizedDay);
        }
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
            results.add(executeReviewedAction(index + 1, intent, mode, userId, plan.confidence()));
        }

        return summarizeReviewedResults(mode, results);
    }

    private AgentActionResult executeReviewedAction(int index, CalendarAgentIntent intent, String mode, Long userId, Double planConfidence) {
        String action = normalizeAction(intent.action());
        if (!ACTION_NONE.equals(action) && isBelowConfidenceThreshold(effectiveConfidence(planConfidence, intent), reviewConfidenceThreshold)) {
            return new AgentActionResult(
                    index,
                    action,
                    false,
                    false,
                    "识别置信度较低，请确认语音文本是否准确后重试。",
                    null,
                    List.of(),
                    null,
                    null
            );
        }

        try {
            AgentChatResponse response = switch (action) {
                case ACTION_CREATE -> prepareCreate(userId, intent, mode);
                case ACTION_QUERY -> prepareQuery(userId, intent, mode);
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
                    null,
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

    private AgentChatResponse prepareCreate(Long userId, CalendarAgentIntent intent, String mode) {
        if (isRecurringIntent(intent)) {
            return prepareRecurringCreate(userId, intent);
        }

        if (isBlank(intent.title()) || isBlank(intent.date()) || isBlank(intent.startTime())) {
            return AgentChatResponse.failed("缺少创建日程所需的标题、日期或开始时间，请再说得具体一点。", mode, ACTION_CREATE, List.of());
        }

        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
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
        ));
        return AgentChatResponse.confirmation(
                "将添加这个日程，请确认：\n" + formatPendingEvent(pendingAction) + "\n确认操作会自动过期，请尽快确认。",
                ACTION_CREATE,
                List.of(),
                pendingAction
        );
    }

    private AgentChatResponse prepareRecurringCreate(Long userId, CalendarAgentIntent intent) {
        if (isBlank(intent.title()) || isBlank(intent.startTime())) {
            return AgentChatResponse.failed("缺少创建重复日程所需的标题或开始时间，请再说得具体一点。", MODE_REVIEW, ACTION_CREATE_RECURRING, List.of());
        }

        String startDate = firstNonBlankOrNull(intent.recurrenceStartDate(), intent.date());
        if (isBlank(startDate)) {
            startDate = LocalDate.now(agentZoneId).toString();
        }
        String endDate = blankToNull(intent.recurrenceEndDate());
        boolean usedDefaultEndDate = false;
        if (endDate == null) {
            endDate = LocalDate.parse(startDate).plusDays(29).toString();
            usedDefaultEndDate = true;
        }

        String recurrenceType = firstNonBlankOrNull(intent.recurrenceType(), "DAILY");
        Integer intervalValue = intent.recurrenceInterval() == null ? 1 : intent.recurrenceInterval();
        PendingRecurringAgentAction pendingAction = confirmationStore.saveRecurring(userId, new PendingRecurringAgentAction(
                null,
                null,
                ACTION_CREATE_RECURRING,
                null,
                intent.title().trim(),
                startDate,
                endDate,
                intent.startTime(),
                blankToNull(intent.endTime()),
                recurrenceType,
                intervalValue,
                normalizeRecurrenceDaysOfWeek(intent.recurrenceDaysOfWeek()),
                blankToNull(intent.location()),
                blankToNull(intent.description()),
                blankToNull(intent.tag()),
                blankToNull(intent.reminderTime())
        ));

        String defaultText = usedDefaultEndDate ? "\n未指定结束日期，系统默认创建未来 30 天。" : "";
        return AgentChatResponse.recurringConfirmation(
                "将添加重复日程，请确认：\n" + formatPendingRecurringEvent(pendingAction) + defaultText + "\n确认操作会自动过期，请尽快确认。",
                ACTION_CREATE_RECURRING,
                pendingAction
        );
    }

    private AgentChatResponse prepareQuery(Long userId, CalendarAgentIntent intent, String mode) {
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                ACTION_QUERY,
                null,
                null,
                blankToNull(intent.date()),
                null,
                null,
                null,
                null,
                null,
                null
        ));
        return AgentChatResponse.confirmation(
                "将查询日程，请确认：\n" + formatPendingQuery(pendingAction) + "\n确认操作会自动过期，请尽快确认。",
                ACTION_QUERY,
                List.of(),
                pendingAction
        );
    }

    private CalendarEvent createEvent(Long userId, PendingAgentAction action) {
        return eventService.createEvent(userId, new EventRequest(
                action.title(),
                toDateTime(action.date(), action.startTime()),
                toOptionalDateTime(action.date(), action.endTime()),
                action.location(),
                action.description(),
                action.tag(),
                toOptionalDateTime(action.date(), action.reminderTime())
        ));
    }

    private RecurringEventResponse createRecurringEvent(Long userId, PendingRecurringAgentAction action) {
        return recurringEventService.createEvent(userId, new RecurringEventRequest(
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
        ));
    }

    private AgentChatResponse queryEvents(Long userId, String dateText, String mode) {
        LocalDate date = isBlank(dateText) ? null : LocalDate.parse(dateText);
        List<CalendarEvent> events = eventService.findEvents(userId, date);
        if (events.isEmpty()) {
            String message = date == null ? "当前没有日程。" : "这一天没有日程：" + date;
            return AgentChatResponse.done(message, mode, ACTION_QUERY, null, events);
        }

        String message = "找到 " + events.size() + " 个日程：\n" + formatEvents(events);
        return AgentChatResponse.done(message, mode, ACTION_QUERY, null, events);
    }

    private AgentChatResponse prepareUpdate(Long userId, CalendarAgentIntent intent, String mode) {
        if (isRecurringIntent(intent)) {
            return AgentChatResponse.failed("识别到重复日程修改意图。当前暂不支持通过 Agent 修改整条重复规则，请先手动处理，或删除后重新创建。", mode, ACTION_UPDATE, List.of());
        }
        if (!hasAnyUpdateField(intent)) {
            return AgentChatResponse.failed("没有识别到要修改成什么内容，请说出新的时间、标题或地点。", mode, ACTION_UPDATE, List.of());
        }
        if (!hasAnyTargetLocator(intent)) {
            return AgentChatResponse.failed(UNCLEAR_TARGET_MESSAGE, mode, ACTION_UPDATE, List.of());
        }

        List<CalendarEvent> allCandidates = findCandidates(userId, intent);
        List<CalendarEvent> candidates = singleEventCandidates(allCandidates);
        if (candidates.isEmpty()) {
            if (hasRecurringEventInstance(allCandidates)) {
                return AgentChatResponse.failed("找到的是重复日程实例，不能按普通单次日程修改。当前暂不支持修改重复规则，请先手动处理，或删除后重新创建。", mode, ACTION_UPDATE, List.of());
            }
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
        if (isRecurringIntent(intent)) {
            return prepareRecurringDelete(userId, intent, mode);
        }

        if (!hasAnyTargetLocator(intent)) {
            return AgentChatResponse.failed(UNCLEAR_TARGET_MESSAGE, mode, ACTION_DELETE, List.of());
        }

        List<CalendarEvent> allCandidates = findCandidates(userId, intent);
        List<CalendarEvent> candidates = singleEventCandidates(allCandidates);
        if (candidates.isEmpty()) {
            if (hasRecurringEventInstance(allCandidates)) {
                return AgentChatResponse.failed("找到的是重复日程实例，不能按普通单次日程删除。请明确说明要删除整条重复规则，例如“删除每天背单词这个重复日程”。", mode, ACTION_DELETE, List.of());
            }
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

    private AgentChatResponse prepareRecurringDelete(Long userId, CalendarAgentIntent intent, String mode) {
        if (!hasAnyRecurringTargetLocator(intent)) {
            return AgentChatResponse.failed("无法确定要删除哪条重复日程规则，请提供标题、重复频率或时间范围后重试。", mode, ACTION_DELETE_RECURRING, List.of());
        }

        List<RecurringEventResponse> candidates = findRecurringCandidates(userId, intent);
        if (candidates.isEmpty()) {
            return AgentChatResponse.failed("没有找到符合条件的重复日程规则，无法删除。", mode, ACTION_DELETE_RECURRING, List.of());
        }
        if (candidates.size() > 1) {
            return AgentChatResponse.failed("找到多条符合条件的重复日程规则，请补充标题、时间或频率后再删除。", mode, ACTION_DELETE_RECURRING, List.of());
        }

        RecurringEventResponse target = candidates.getFirst();
        PendingRecurringAgentAction pendingAction = confirmationStore.saveRecurring(userId, new PendingRecurringAgentAction(
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
        ));

        return AgentChatResponse.recurringConfirmation(
                "将删除整条重复日程规则，请确认：\n" + formatPendingRecurringEvent(pendingAction) + "\n确认后该规则下的所有未来展示实例都会消失。确认操作会自动过期，请尽快确认。",
                ACTION_DELETE_RECURRING,
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
        String targetStartTimeFrom = blankToNull(intent.targetStartTimeFrom());
        String targetStartTimeTo = blankToNull(intent.targetStartTimeTo());

        return events.stream()
                .filter(event -> matchesTargetKeyword(event, keyword))
                .filter(event -> matchesExactStartTime(event, targetStartTime))
                .filter(event -> matchesStartTimeRange(event, targetStartTimeFrom, targetStartTimeTo))
                .toList();
    }

    private List<RecurringEventResponse> findRecurringCandidates(Long userId, CalendarAgentIntent intent) {
        String keyword = firstNonBlankOrNull(intent.targetTitleKeyword(), intent.title());
        String recurrenceType = blankToNull(intent.recurrenceType());
        String targetStartTime = firstNonBlankOrNull(intent.targetStartTime(), intent.startTime());
        LocalDate rangeStart = parseOptionalDate(firstNonBlankOrNull(intent.recurrenceStartDate(), intent.date()));
        LocalDate rangeEnd = parseOptionalDate(intent.recurrenceEndDate());
        List<String> daysOfWeek = normalizeRecurrenceDaysOfWeek(intent.recurrenceDaysOfWeek());

        return recurringEventService.findEvents(userId).stream()
                .filter(event -> matchesRecurringKeyword(event, keyword))
                .filter(event -> matchesRecurringType(event, recurrenceType))
                .filter(event -> matchesRecurringDateRange(event, rangeStart, rangeEnd))
                .filter(event -> matchesRecurringStartTime(event, targetStartTime))
                .filter(event -> matchesRecurringDaysOfWeek(event, daysOfWeek))
                .toList();
    }

    private boolean hasAnyRecurringTargetLocator(CalendarAgentIntent intent) {
        return !isBlank(intent.targetTitleKeyword())
                || !isBlank(intent.title())
                || !isBlank(intent.date())
                || !isBlank(intent.startTime())
                || !isBlank(intent.targetStartTime())
                || !isBlank(intent.recurrenceType())
                || !isBlank(intent.recurrenceStartDate())
                || !isBlank(intent.recurrenceEndDate())
                || (intent.recurrenceDaysOfWeek() != null && !intent.recurrenceDaysOfWeek().isEmpty());
    }

    private List<CalendarEvent> singleEventCandidates(List<CalendarEvent> candidates) {
        return candidates.stream()
                .filter(event -> !isRecurringEventInstance(event))
                .toList();
    }

    private boolean hasRecurringEventInstance(List<CalendarEvent> candidates) {
        return candidates.stream().anyMatch(this::isRecurringEventInstance);
    }

    private boolean isRecurringEventInstance(CalendarEvent event) {
        return SOURCE_TYPE_RECURRING.equalsIgnoreCase(blankToNull(event.sourceType()))
                || (event.id() == null && event.recurringEventId() != null);
    }

    private boolean matchesRecurringKeyword(RecurringEventResponse event, String keyword) {
        if (isBlank(keyword)) {
            return true;
        }
        return containsIgnoreCase(event.title(), keyword)
                || containsIgnoreCase(event.location(), keyword)
                || containsIgnoreCase(event.description(), keyword)
                || containsIgnoreCase(event.tag(), keyword);
    }

    private boolean matchesRecurringType(RecurringEventResponse event, String recurrenceType) {
        return isBlank(recurrenceType) || recurrenceType.equalsIgnoreCase(event.recurrenceType());
    }

    private boolean matchesRecurringDateRange(RecurringEventResponse event, LocalDate rangeStart, LocalDate rangeEnd) {
        if (rangeStart != null && event.endDate().isBefore(rangeStart)) {
            return false;
        }
        return rangeEnd == null || !event.startDate().isAfter(rangeEnd);
    }

    private boolean matchesRecurringStartTime(RecurringEventResponse event, String targetStartTime) {
        return isBlank(targetStartTime) || event.startTime().equals(LocalTime.parse(targetStartTime));
    }

    private boolean matchesRecurringDaysOfWeek(RecurringEventResponse event, List<String> targetDaysOfWeek) {
        if (targetDaysOfWeek == null || targetDaysOfWeek.isEmpty()) {
            return true;
        }
        List<String> eventDaysOfWeek = parseDaysList(event.daysOfWeek()).stream()
                .map(day -> day.toUpperCase(Locale.ROOT))
                .toList();
        return eventDaysOfWeek.containsAll(targetDaysOfWeek.stream()
                .map(day -> day.toUpperCase(Locale.ROOT))
                .toList());
    }

    private LocalDate parseOptionalDate(String date) {
        return isBlank(date) ? null : LocalDate.parse(date);
    }

    private boolean matchesExactStartTime(CalendarEvent event, String targetStartTime) {
        return isBlank(targetStartTime) || event.startTime().toLocalTime().equals(LocalTime.parse(targetStartTime));
    }

    private boolean matchesStartTimeRange(CalendarEvent event, String from, String to) {
        LocalTime eventStartTime = event.startTime().toLocalTime();
        if (!isBlank(from) && eventStartTime.isBefore(LocalTime.parse(from))) {
            return false;
        }
        return isBlank(to) || !eventStartTime.isAfter(LocalTime.parse(to));
    }

    private boolean matchesTargetKeyword(CalendarEvent event, String keyword) {
        if (keyword == null) {
            return true;
        }
        return containsIgnoreCase(event.title(), keyword)
                || containsIgnoreCase(event.location(), keyword)
                || containsIgnoreCase(event.description(), keyword)
                || containsIgnoreCase(event.tag(), keyword)
                || (isMeetingKeyword(keyword) && isMeetingEvent(event));
    }

    private boolean isMeetingEvent(CalendarEvent event) {
        return isMeetingKeyword(event.title())
                || isMeetingKeyword(event.location())
                || isMeetingKeyword(event.description())
                || isMeetingKeyword(event.tag());
    }

    private boolean isMeetingKeyword(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return false;
        }
        if ("会".equals(normalized)) {
            return true;
        }
        return MEETING_KEYWORDS.stream().anyMatch(normalized::contains);
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
                || !isBlank(intent.targetStartTimeFrom())
                || !isBlank(intent.targetStartTimeTo())
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

    private String formatPendingEvent(PendingAgentAction action) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", action.title());
        appendField(builder, "时间", formatPendingDateTimeRange(action.date(), action.startTime(), action.endTime()));
        appendField(builder, "地点", action.location());
        appendField(builder, "标签", action.tag());
        appendField(builder, "提醒", formatPendingDateTime(action.date(), action.reminderTime()));
        appendField(builder, "备注", action.description());
        return builder.toString();
    }

    private String formatPendingRecurringEvent(PendingRecurringAgentAction action) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, "标题", action.title());
        appendField(builder, "频率", formatRecurrence(action.recurrenceType(), action.intervalValue(), action.daysOfWeek()));
        appendField(builder, "范围", action.startDate() + " 至 " + action.endDate());
        appendField(builder, "时间", formatTimeRange(action.startTime(), action.endTime()));
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
        appendField(builder, "时间", formatTimeRange(
                event.startTime() == null ? null : event.startTime().toString(),
                event.endTime() == null ? null : event.endTime().toString()
        ));
        appendField(builder, "地点", event.location());
        appendField(builder, "标签", event.tag());
        appendField(builder, "提醒", event.reminderTime() == null ? null : event.reminderTime().toString());
        appendField(builder, "备注", event.description());
        return builder.toString();
    }

    private String formatRecurrence(String recurrenceType, Integer intervalValue, List<String> daysOfWeek) {
        String type = blankToNull(recurrenceType);
        int interval = intervalValue == null ? 1 : intervalValue;
        if ("WEEKLY".equalsIgnoreCase(type)) {
            String days = daysOfWeek == null || daysOfWeek.isEmpty() ? "" : "（" + String.join("、", daysOfWeek) + "）";
            return interval == 1 ? "每周" + days : "每 " + interval + " 周" + days;
        }
        if ("MONTHLY".equalsIgnoreCase(type)) {
            return interval == 1 ? "每月" : "每 " + interval + " 月";
        }
        return interval == 1 ? "每天" : "每 " + interval + " 天";
    }

    private String formatTimeRange(String startTime, String endTime) {
        if (isBlank(startTime)) {
            return null;
        }
        return isBlank(endTime) ? startTime.trim() : startTime.trim() + "-" + endTime.trim();
    }

    private String formatPendingQuery(PendingAgentAction action) {
        return isBlank(action.date()) ? "范围：全部日程" : "日期：" + action.date().trim();
    }

    private String formatPendingDateTimeRange(String date, String startTime, String endTime) {
        if (isBlank(date) || isBlank(startTime)) {
            return null;
        }
        String start = date.trim() + " " + startTime.trim();
        if (isBlank(endTime)) {
            return start;
        }
        return start + "-" + endTime.trim();
    }

    private String formatPendingDateTime(String date, String time) {
        if (isBlank(date) || isBlank(time)) {
            return null;
        }
        return date.trim() + " " + time.trim();
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

    private Double effectiveConfidence(CalendarAgentPlan plan, CalendarAgentIntent intent) {
        return effectiveConfidence(plan.confidence(), intent);
    }

    private Double effectiveConfidence(Double planConfidence, CalendarAgentIntent intent) {
        return intent.confidence() == null ? planConfidence : intent.confidence();
    }

    private boolean isBelowConfidenceThreshold(Double confidence, double threshold) {
        return confidence == null || confidence < threshold;
    }

    private boolean isRecurringIntent(CalendarAgentIntent intent) {
        return Boolean.TRUE.equals(intent.recurring())
                || !isBlank(intent.recurrenceType())
                || !isBlank(intent.recurrenceStartDate())
                || !isBlank(intent.recurrenceEndDate())
                || intent.recurrenceInterval() != null
                || (intent.recurrenceDaysOfWeek() != null && !intent.recurrenceDaysOfWeek().isEmpty());
    }

    private List<String> normalizeRecurrenceDaysOfWeek(List<String> daysOfWeek) {
        if (daysOfWeek == null) {
            return List.of();
        }
        return daysOfWeek.stream()
                .filter(day -> !isBlank(day))
                .map(String::trim)
                .toList();
    }

    private List<String> parseDaysList(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return List.of();
        }
        return List.of(normalized.split(","));
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
