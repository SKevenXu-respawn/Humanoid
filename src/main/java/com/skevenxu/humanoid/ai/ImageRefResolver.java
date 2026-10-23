package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.entity.BotImage;
import com.skevenxu.humanoid.mapper.BotImageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * 把 bot_context.image_refs 中的引用解析成 base64 data URL。
 * 支持：image:<bot_image.id>、http(s) 链接、用户沙箱路径（skills/、tools/ 也可）。
 */
@Component
public class ImageRefResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageRefResolver.class);
    private static final String IMAGE_ID_PREFIX = "image:";

    private final BotImageMapper imageMapper;
    private final ImageDataLoader imageDataLoader;
    private final FileSandbox sandbox;
    private final int maxBytes;

    public ImageRefResolver(BotImageMapper imageMapper,
                            ImageDataLoader imageDataLoader,
                            FileSandbox sandbox,
                            @Value("${humanoid.ai.max-image-bytes:10485760}") int maxBytes) {
        this.imageMapper = imageMapper;
        this.imageDataLoader = imageDataLoader;
        this.sandbox = sandbox;
        this.maxBytes = maxBytes;
    }

    public Optional<String> resolve(Long userId, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String value = ref.trim();
        if (value.startsWith(IMAGE_ID_PREFIX)) {
            try {
                long id = Long.parseLong(value.substring(IMAGE_ID_PREFIX.length()));
                BotImage image = imageMapper.selectById(id);
                return image == null || image.getDataUrl() == null
                        ? Optional.empty()
                        : Optional.of(image.getDataUrl());
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("base64://")) {
            return imageDataLoader.toDataUrl(value);
        }
        return readSandboxImage(userId, value);
    }

    private Optional<String> readSandboxImage(Long userId, String path) {
        try {
            Path target = sandbox.resolve(userId, path);
            if (!Files.isRegularFile(target) || Files.size(target) > maxBytes) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(target);
            return Optional.of("data:" + ImageDataLoader.detectMime(bytes)
                    + ";base64," + Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            log.warn("上下文图片读取失败 ref={}: {}", path, e.toString());
            return Optional.empty();
        }
    }
}
