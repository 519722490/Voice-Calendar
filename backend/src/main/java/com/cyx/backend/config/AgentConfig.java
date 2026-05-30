package com.cyx.backend.config;

import com.cyx.backend.tool.CalendarEventTools;
import com.cyx.backend.tool.CurrentTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {
    @Bean("voiceCalendarAutoChatClient")
    @ConditionalOnProperty(prefix = "voice-calendar.ai", name = "enabled", havingValue = "true")
    public ChatClient voiceCalendarAutoChatClient(
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
                        5. 创建日程时，必须为 tag 选择一个固定标签，只能从“会议、工作、学习、生活、运动、出行、提醒、其他”中选择；识别不出来就用“其他”。
                        6. 标签示例：开会、评审、讨论、汇报 => 会议；去工位、写代码、项目开发 => 工作；上课、复习、考试 => 学习；吃饭、购物、看病 => 生活；跑步、健身、打球 => 运动；出差、坐车、去机场 => 出行；提醒我、记得、闹钟 => 提醒。
                        7. 创建日程时，结束时间、地点、备注、提醒时间都是可选字段；用户没说就留空，不要追问。
                        8. 只有在无法识别日期、开始时间，或完全没有日程管理意图时，才不要调用工具，并回复“未识别到明确的日程管理信息，请说明要添加、查看、修改或删除的日程。”
                        9. 修改或删除日程前，如果用户描述不够明确，先查询候选日程并让用户确认，避免误操作。
                        10. 回答要简洁，明确告诉用户操作结果。
                        """)
                .defaultTools(calendarEventTools, currentTimeTools)
                .build();
    }

    @Bean("voiceCalendarReviewChatClient")
    @ConditionalOnProperty(prefix = "voice-calendar.ai", name = "enabled", havingValue = "true")
    public ChatClient voiceCalendarReviewChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个语音日历意图解析器，只负责把用户输入解析成 JSON，不允许执行任何真实操作。
                        必须只输出一个合法 JSON 对象，不要输出 Markdown、解释文字或代码块。
                        """)
                .build();
    }
}
