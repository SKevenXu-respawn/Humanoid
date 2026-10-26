package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.video.VideoQueueService;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 主动刷视频时，把有趣的视频加入分享队列（由队列按顺序发送）。 */
@Component
public class ShareVideoTool {

    private static final Logger log = LoggerFactory.getLogger(ShareVideoTool.class);

    private final VideoQueueService videoQueueService;

    public ShareVideoTool(VideoQueueService videoQueueService) {
        this.videoQueueService = videoQueueService;
    }

    @Tool(description = """
            把发现的视频加入分享队列（不会立即发送，系统会按入队顺序发送给当前用户）。
            url 为视频链接，title 为视频标题，comment 为一句自然的推荐语。
            只在视频确实有趣时加入，每轮主动行为最多调用一次。""")
    public String shareVideo(@ToolParam(description = "视频链接，必须 http/https") String url,
                             @ToolParam(description = "视频标题") String title,
                             @ToolParam(description = "一句推荐语") String comment) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        boolean added;
        try {
            added = videoQueueService.enqueue(scope.userId(), scope.messageType(), scope.groupId(),
                    url, title, comment, BotVideoQueue.SOURCE_PROACTIVE);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
        long pending = videoQueueService.countPending(scope);
        log.info("工具调用 shareVideo user_id={} group_id={} url={} added={} pending={}",
                scope.userId(), scope.groupId(), url, added, pending);
        return (added ? "已加入视频队列" : "这个视频已经在队列中")
                + "，当前待发送 " + pending + " 条";
    }
}
