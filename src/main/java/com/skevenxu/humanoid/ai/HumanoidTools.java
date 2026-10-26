package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.ai.tools.ChatMessageTool;
import com.skevenxu.humanoid.ai.tools.DownloadTool;
import com.skevenxu.humanoid.ai.tools.HumanoidSkillsTool;
import com.skevenxu.humanoid.ai.tools.ImageViewTool;
import com.skevenxu.humanoid.ai.tools.ReminderTool;
import com.skevenxu.humanoid.ai.tools.SandboxFileTools;
import com.skevenxu.humanoid.ai.tools.SendFileTool;
import com.skevenxu.humanoid.ai.tools.SendMessagesTool;
import com.skevenxu.humanoid.ai.tools.ShareVideoTool;
import com.skevenxu.humanoid.ai.tools.ShellTool;
import com.skevenxu.humanoid.ai.tools.SvgTool;
import com.skevenxu.humanoid.ai.tools.TaskTool;
import com.skevenxu.humanoid.ai.tools.TimeTool;
import com.skevenxu.humanoid.ai.tools.ToolCatalogTool;
import com.skevenxu.humanoid.ai.tools.VoiceTool;
import com.skevenxu.humanoid.ai.tools.VideoQueueTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.BraveWebSearchTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外暴露给模型的工具集合。
 * 文件工具按 QQ 号隔离：每个用户有独立工作区，skills/ 与 tools/ 为共享目录。
 */
@Component
public class HumanoidTools {

    private static final Logger log = LoggerFactory.getLogger(HumanoidTools.class);

    private final TimeTool timeTool;
    private final ReminderTool reminderTool;
    private final ChatMessageTool chatMessageTool;
    private final VoiceTool voiceTool;
    private final VideoQueueTool videoQueueTool;
    private final SendFileTool sendFileTool;
    private final SendMessagesTool sendMessagesTool;
    private final ShareVideoTool shareVideoTool;
    private final ImageViewTool imageViewTool;
    private final DownloadTool downloadTool;
    private final TaskTool taskTool;
    private final ShellTool shellTool;
    private final HumanoidSkillsTool skillsTool;
    private final SvgTool svgTool;
    private final SandboxFileTools sandboxFileTools;
    private final FileSandbox sandbox;
    private final String braveApiKey;

    public HumanoidTools(TimeTool timeTool,
                         ReminderTool reminderTool,
                         ChatMessageTool chatMessageTool,
                         VoiceTool voiceTool,
                         VideoQueueTool videoQueueTool,
                         SendFileTool sendFileTool,
                         SendMessagesTool sendMessagesTool,
                         ShareVideoTool shareVideoTool,
                         ImageViewTool imageViewTool,
                         DownloadTool downloadTool,
                         TaskTool taskTool,
                         ShellTool shellTool,
                         HumanoidSkillsTool skillsTool,
                         SvgTool svgTool,
                         SandboxFileTools sandboxFileTools,
                         FileSandbox sandbox,
                         @Value("${humanoid.tools.brave-api-key:}") String braveApiKey) {
        this.timeTool = timeTool;
        this.reminderTool = reminderTool;
        this.chatMessageTool = chatMessageTool;
        this.voiceTool = voiceTool;
        this.videoQueueTool = videoQueueTool;
        this.sendFileTool = sendFileTool;
        this.sendMessagesTool = sendMessagesTool;
        this.shareVideoTool = shareVideoTool;
        this.imageViewTool = imageViewTool;
        this.downloadTool = downloadTool;
        this.taskTool = taskTool;
        this.shellTool = shellTool;
        this.skillsTool = skillsTool;
        this.svgTool = svgTool;
        this.sandboxFileTools = sandboxFileTools;
        this.sandbox = sandbox;
        this.braveApiKey = braveApiKey;
        log.info("文件沙箱: base={} skills={} tools={}",
                sandbox.baseDir(), sandbox.skillsDir(), sandbox.toolsDir());
    }

    /** 当前用户的私有工作区（会按需创建）。 */
    public String userRoot(Long userId) {
        return sandbox.userDir(userId).toString();
    }

    /** 共享目录说明，供系统提示词使用。 */
    public String sharedDirs() {
        return "skills=" + sandbox.skillsDir() + "，tools=" + sandbox.toolsDir();
    }

    /** 供系统提示词使用：当前可用技能清单。 */
    public String skillsBrief() {
        return skillsTool.skillsBrief();
    }

    /**
     * @param summarizerClient 供 SmartWebFetchTool 使用的无工具 ChatClient，避免递归调用工具
     */
    public List<Object> create(ChatClient summarizerClient) {
        List<Object> tools = new ArrayList<>();
        tools.add(timeTool);
        tools.add(reminderTool);
        tools.add(chatMessageTool);
        tools.add(voiceTool);
        tools.add(videoQueueTool);
        tools.add(sendFileTool);
        tools.add(sendMessagesTool);
        tools.add(imageViewTool);
        tools.add(downloadTool);
        tools.add(taskTool);
        tools.add(shellTool);
        tools.add(skillsTool);
        tools.add(svgTool);
        tools.add(sandboxFileTools);
        tools.addAll(webTools(summarizerClient));
        return withToolCatalog(tools);
    }

    /**
     * 后台任务执行器的工具集：只保留干活用的工具，
     * 不允许执行器直接发消息/图片/表情或设提醒（进度通知由 TaskExecutionService 统一发送）。
     */
    public List<Object> createForExecutor(ChatClient summarizerClient) {
        List<Object> tools = new ArrayList<>();
        tools.add(timeTool);
        tools.add(imageViewTool);
        tools.add(taskTool);
        tools.add(downloadTool);
        tools.add(sandboxFileTools);
        tools.addAll(webTools(summarizerClient));
        return withToolCatalog(tools);
    }

    /**
     * 主动行为（proactive）使用的工具集：只保留时间、任务、技能、图片查看和网页抓取，
     * 不提供 shell、文件写入、发消息等高风险工具；浏览器工具由调用方自行追加。
     */
    public List<Object> createForProactive(ChatClient summarizerClient) {
        List<Object> tools = new ArrayList<>();
        tools.add(timeTool);
        tools.add(taskTool);
        tools.add(skillsTool);
        tools.add(imageViewTool);
        tools.add(shareVideoTool);
        tools.add(SmartWebFetchTool.builder(summarizerClient).build());
        return tools;
    }

    /** 把当前工具清单做成 listTools 工具，模型可自查能力。 */
    private List<Object> withToolCatalog(List<Object> tools) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build()
                .getToolCallbacks();

        List<String> lines = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            String description = callback.getToolDefinition().description();
            String firstLine = description == null ? "" : description.lines().findFirst().orElse("");
            if (firstLine.length() > 120) {
                firstLine = PromptSanitizer.truncate(firstLine, 120);
            }
            lines.add("- " + callback.getToolDefinition().name() + "：" + firstLine);
        }
        lines.add("- listTools：列出当前 AI 可用的所有工具及其用途。");
        tools.add(new ToolCatalogTool(() -> lines));
        return tools;
    }

    private List<Object> webTools(ChatClient summarizerClient) {
        List<Object> tools = new ArrayList<>();
        tools.add(SmartWebFetchTool.builder(summarizerClient).build());
        tools.add(TodoWriteTool.builder().build());
        if (!braveApiKey.isBlank()) {
            tools.add(BraveWebSearchTool.builder(braveApiKey).build());
        }
        return tools;
    }
}
