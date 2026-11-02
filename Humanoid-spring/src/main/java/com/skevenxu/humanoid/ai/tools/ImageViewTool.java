package com.skevenxu.humanoid.ai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.ai.DeepSeekClient;
import com.skevenxu.humanoid.entity.BotImage;
import com.skevenxu.humanoid.mapper.BotImageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 查看已存储图片：把 base64 交给视觉模型生成描述。 */
@Component
public class ImageViewTool {

    private static final Logger log = LoggerFactory.getLogger(ImageViewTool.class);
    private static final String SYSTEM_PROMPT = "你是一个图片描述助手，请客观、简洁地描述图片的主要内容。";

    private final BotImageMapper imageMapper;
    private final DeepSeekClient deepSeekClient;

    public ImageViewTool(BotImageMapper imageMapper, DeepSeekClient deepSeekClient) {
        this.imageMapper = imageMapper;
        this.deepSeekClient = deepSeekClient;
    }

    @Tool(description = """
            查看之前发送过的图片内容（基于已存储的 base64）。
            imageId 省略时查看最近一张发送过的图片；返回图片的文字描述。""")
    public String describeImage(@ToolParam(description = "图片编号，可选", required = false) Long imageId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        long groupId = "group".equals(scope.messageType()) && scope.groupId() != null
                ? scope.groupId() : 0L;

        LambdaQueryWrapper<BotImage> query = new LambdaQueryWrapper<BotImage>()
                .eq(BotImage::getUserId, scope.userId())
                .eq(BotImage::getMessageType, scope.messageType())
                .eq(BotImage::getGroupId, groupId);
        BotImage image;
        if (imageId == null) {
            image = imageMapper.selectOne(query.orderByDesc(BotImage::getId).last("LIMIT 1"));
        } else {
            image = imageMapper.selectOne(query.eq(BotImage::getId, imageId));
        }
        if (image == null) {
            return imageId == null ? "当前没有存储的图片" : "错误：没有找到图片 #" + imageId;
        }

        Optional<String> description = deepSeekClient.chat(SYSTEM_PROMPT,
                List.of(DeepSeekClient.ChatMessage.user(
                        "请描述这张图片的主要内容。", List.of(image.getDataUrl()))));
        if (description.isEmpty() || description.get().isBlank()) {
            return "错误：图片描述失败";
        }

        log.info("工具调用 describeImage image_id={}", image.getId());
        return "图片 #" + image.getId() + "（来源：" + image.getSource() + "）：\n" + description.get().trim();
    }
}
