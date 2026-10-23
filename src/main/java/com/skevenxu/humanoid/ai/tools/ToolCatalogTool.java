package com.skevenxu.humanoid.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.function.Supplier;

/** 工具清单：返回当前 AI 实际可用的工具及用途。 */
public class ToolCatalogTool {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogTool.class);

    private final Supplier<List<String>> tools;

    public ToolCatalogTool(Supplier<List<String>> tools) {
        this.tools = tools;
    }

    @Tool(description = "列出当前 AI 可用的所有工具及其用途。当你不确定自己有哪些能力时，先调用这个工具。")
    public String listTools() {
        List<String> lines = tools.get();
        log.info("工具调用 listTools, 工具数={}", lines.size());
        if (lines.isEmpty()) {
            return "当前没有可用工具";
        }
        return "当前可用工具（共 " + lines.size() + " 个）：\n" + String.join("\n", lines);
    }
}
