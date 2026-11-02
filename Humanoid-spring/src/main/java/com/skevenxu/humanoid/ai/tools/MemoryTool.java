package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** 长期记忆工具：AI 自主记录私有/公共记忆，也可按关键词回忆。 */
@Component
public class MemoryTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private final MemoryService memoryService;

    public MemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = """
            记录关于当前用户的私有长期记忆，只在私聊里使用，不会出现在群聊中。
            适合稳定偏好、重要个人信息、长期目标等；不要记录一次性闲聊。
            importance 为 1-5，默认 3（越重要越优先出现在上下文里）。""")
    public String rememberPrivate(
            @ToolParam(description = "要记住的内容") String content,
            @ToolParam(required = false, description = "重要程度 1-5，默认 3") Integer importance) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        try {
            boolean saved = memoryService.rememberPrivate(scope, content, importance, null);
            return saved ? "已记住（私有）" : "这条记忆已经存在";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(description = """
            在群聊中记录公共记忆或关系记忆，全群可见；只能在群聊中使用。
            participants 填这条记忆涉及的 QQ 号（可省略）；不填表示群公共记忆，
            填写多个人表示关系记忆（会自动包含当前发言者）。不要在私聊里记录公共记忆。""")
    public String rememberShared(
            @ToolParam(description = "要记住的公共/关系内容") String content,
            @ToolParam(required = false, description = "涉及的 QQ 号列表，可省略") List<Long> participants) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        try {
            boolean saved = memoryService.rememberShared(scope, content, participants, null);
            return saved ? "已记住（公共）" : "这条记忆已经存在";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(description = """
            按关键词检索当前会话可见的长期记忆；keyword 为空时返回最近的记忆。
            用户提到“你记得吗/之前说过/还记得”时使用。""")
    public String recallMemory(
            @ToolParam(required = false, description = "关键词，可省略") String keyword) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        log.info("工具调用 recallMemory user_id={} group_id={} keyword={}",
                scope.userId(), scope.groupId(), keyword);
        return memoryService.recallText(scope, keyword);
    }
}
