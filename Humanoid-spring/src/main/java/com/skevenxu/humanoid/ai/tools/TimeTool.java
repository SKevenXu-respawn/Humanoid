package com.skevenxu.humanoid.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** 示例工具：验证 Spring AI tool calling 链路，可按需删除或添加自己的工具。 */
@Component
public class TimeTool {

    private static final Logger log = LoggerFactory.getLogger(TimeTool.class);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(description = "获取服务器当前的日期和时间（北京时间）")
    public String currentTime() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        String result = now.format(FORMAT) + "（" + weekday + "）";
        log.info("工具调用 currentTime -> {}", result);
        return result;
    }
}
