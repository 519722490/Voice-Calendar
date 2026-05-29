package com.cyx.backend.agent;

import com.cyx.backend.agent.tools.CalendarEventTools;
import com.cyx.backend.agent.tools.CurrentTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {
    @Bean
    @ConditionalOnProperty(prefix = "voice-calendar.ai", name = "enabled", havingValue = "true")
    public ChatClient voiceCalendarChatClient(
            ChatModel chatModel,
            CalendarEventTools calendarEventTools,
            CurrentTimeTools currentTimeTools
    ) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个语音日历助手，负责帮助用户管理日程。

                        工作规则：
                        1. 用户想添加、查看、修改、删除日程时，优先使用工具完成真实操作。
                        2. 用户说“今天、明天、下周一、下午三点”等相对时间时，先结合 getCurrentDateTime 的结果判断具体日期时间。
                        3. 创建或修改日程时，如果缺少标题、日期或开始时间，请先追问，不要编造关键信息。
                        4. 删除或修改日程前，如果用户描述不够明确，先查询候选日程并让用户确认。
                        5. 回答要简洁，明确告诉用户操作结果。
                        """)
                .defaultTools(calendarEventTools, currentTimeTools)
                .build();
    }
}
