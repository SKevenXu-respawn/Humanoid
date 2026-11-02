package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.svg.SvgRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Base64;

/** 把 SVG/XML 字符串渲染成 PNG 并发送到当前聊天。 */
@Component
public class SvgTool {

    private static final Logger log = LoggerFactory.getLogger(SvgTool.class);

    private final SvgRenderer renderer;
    private final ChatMessageTool chatMessageTool;

    public SvgTool(SvgRenderer renderer, ChatMessageTool chatMessageTool) {
        this.renderer = renderer;
        this.chatMessageTool = chatMessageTool;
    }

    @Tool(description = """
            根据 SVG/XML 字符串绘制图片并发送到当前聊天。
            svg 必须是完整的 <svg ...>...</svg>；width / height 为可选的像素尺寸（16-4096，缺省宽度 800）。
            适合画图表、示意图、简单插画。绘制完成后不需要再用 sendImage 发送。""")
    public String renderSvg(@ToolParam(description = "完整的 SVG 字符串") String svg,
                            @ToolParam(description = "输出宽度（像素），可选", required = false) Integer width,
                            @ToolParam(description = "输出高度（像素），可选", required = false) Integer height) {
        if (ToolContext.get() == null) {
            return "错误：无法确定当前会话";
        }
        try {
            byte[] png = renderer.render(svg, width, height);
            String dataUrl = "base64://" + Base64.getEncoder().encodeToString(png);
            String result = chatMessageTool.sendImage(dataUrl);
            log.info("工具调用 renderSvg bytes={} result={}", png.length, result);
            return result;
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("SVG 渲染失败: {}", e.toString());
            return "错误：SVG 渲染失败 " + e.getMessage();
        }
    }
}
