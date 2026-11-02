package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.video.VideoQueueService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 视频队列工具：查询未发送数量、立即取下一个视频。 */
@Component
public class VideoQueueTool {

    private final VideoQueueService videoQueueService;

    public VideoQueueTool(VideoQueueService videoQueueService) {
        this.videoQueueService = videoQueueService;
    }

    @Tool(description = """
            查询视频分享队列。返回当前会话还有多少未发送的视频、队列总数以及下一个视频标题。
            用户问“还有多少视频没看/队列里还有多少”时使用。""")
    public String videoQueueStatus() {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        long mine = videoQueueService.countPending(scope);
        long all = videoQueueService.countPendingAll();
        String next = videoQueueService.peek(scope)
                .map(item -> item.getTitle() == null || item.getTitle().isBlank()
                        ? "（无标题）" : item.getTitle())
                .orElse(null);
        StringBuilder sb = new StringBuilder("你还有 ").append(mine).append(" 个视频没看");
        if (next != null) {
            sb.append("，下一个是《").append(next).append("》");
        }
        sb.append("；队列中共有 ").append(all).append(" 个待发送视频。");
        return sb.toString();
    }

    @Tool(description = """
            立即发送当前会话队列中的下一个视频。用户说“给我看下一个视频/现在发一个视频”时使用。
            如果队列为空会返回提示。""")
    public String nextVideo() {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        return videoQueueService.sendNext(scope);
    }
}
