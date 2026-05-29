package com.cyx.backend.tool;

import com.cyx.backend.dto.CurrentTimeResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class CurrentTimeTools {
    @Tool(description = "获取当前日期、时间和时区。用户提到今天、明天、下周等相对时间时，先调用这个工具。")
    public CurrentTimeResult getCurrentDateTime() {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zoneId);
        return new CurrentTimeResult(
                now.toString(),
                LocalDate.now(zoneId).toString(),
                zoneId.toString()
        );
    }
}
