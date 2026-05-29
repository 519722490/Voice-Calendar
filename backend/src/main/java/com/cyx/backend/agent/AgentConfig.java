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
                        1. 这是语音快捷入口。用户想添加、查看、修改、删除日程时，优先使用工具完成真实操作，不要因为缺少非关键字段而频繁追问。
                        2. 用户说“今天、明天、下周一、下午三点”等相对时间时，先调用 getCurrentDateTime 获取当前日期时间，再换算成具体日期时间。
                        3. 创建日程时，只要能识别出日期、开始时间、日程动作或日程内容，就直接创建日程。
                        4. 创建日程时，如果用户没有明确标题，但说了“开会、上课、吃饭、去工位、面试、复习、运动”等动作或内容，就用该动作或内容作为标题。例如“今天下午三点开会”应创建标题为“开会”的日程，不要追问主题。
                        5. 创建日程时，结束时间、地点、备注、提醒时间都是可选字段；用户没说就留空，不要追问。
                        6. 只有在无法识别日期、开始时间，或完全没有日程管理意图时，才不要调用工具，并回复“未识别到明确的日程管理信息，请说明要添加、查看、修改或删除的日程。”
                        7. 修改或删除日程前，如果用户描述不够明确，先查询候选日程并让用户确认，避免误操作。
                        8. 回答要简洁，明确告诉用户操作结果。
                        """)
                .defaultTools(calendarEventTools, currentTimeTools)
                .build();
    }
}
