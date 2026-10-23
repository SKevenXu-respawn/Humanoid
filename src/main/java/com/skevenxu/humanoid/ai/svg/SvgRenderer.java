package com.skevenxu.humanoid.ai.svg;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** 把 SVG/XML 字符串渲染成 PNG。 */
@Component
public class SvgRenderer {

    private static final Logger log = LoggerFactory.getLogger(SvgRenderer.class);
    private static final int MIN_SIZE = 16;
    private static final int MAX_SIZE = 4096;

    /** 外链引用（防 SSRF）。允许 data: 和 #fragment。 */
    private static final Pattern EXTERNAL_HREF = Pattern.compile(
            "(?i)(xlink:)?href\\s*=\\s*[\"']\\s*(https?:)?//");
    private static final Pattern EVENT_ATTR = Pattern.compile("(?i)\\bon[a-z]+\\s*=");

    private final int maxSvgChars;

    public SvgRenderer(@Value("${humanoid.tools.max-svg-chars:200000}") int maxSvgChars) {
        this.maxSvgChars = Math.max(1000, maxSvgChars);
    }

    public byte[] render(String svg, Integer width, Integer height) throws Exception {
        String xml = validate(svg);
        int w = clamp(width, 800);
        int h = clamp(height, 0);

        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) w);
        if (h > 0) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) h);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcoder.transcode(new TranscoderInput(new StringReader(xml)), new TranscoderOutput(out));
        byte[] png = out.toByteArray();
        log.info("SVG 渲染完成 size={}x{} png_bytes={}", w, h > 0 ? h : "auto", png.length);
        return png;
    }

    private String validate(String svg) {
        if (svg == null || svg.isBlank()) {
            throw new IllegalArgumentException("SVG 不能为空");
        }
        if (svg.length() > maxSvgChars) {
            throw new IllegalArgumentException("SVG 过长（最多 " + maxSvgChars + " 字符）");
        }
        String lower = svg.toLowerCase(Locale.ROOT);
        if (!lower.contains("<svg")) {
            throw new IllegalArgumentException("不是有效的 SVG（缺少 <svg> 标签）");
        }
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw new IllegalArgumentException("不允许 DOCTYPE / ENTITY");
        }
        if (lower.contains("<script") || lower.contains("javascript:")) {
            throw new IllegalArgumentException("不允许脚本内容");
        }
        if (EVENT_ATTR.matcher(svg).find()) {
            throw new IllegalArgumentException("不允许事件属性");
        }
        if (EXTERNAL_HREF.matcher(svg).find()) {
            throw new IllegalArgumentException("不允许引用外部资源");
        }
        return svg;
    }

    private static int clamp(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return Math.min(MAX_SIZE, Math.max(MIN_SIZE, value));
    }
}
