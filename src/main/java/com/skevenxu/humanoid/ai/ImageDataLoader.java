package com.skevenxu.humanoid.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 把 QQ 图片地址转换成 DeepSeek 可直接使用的 base64 data URL，并做少量缓存。 */
@Component
public class ImageDataLoader {

    private static final Logger log = LoggerFactory.getLogger(ImageDataLoader.class);
    private static final int CACHE_SIZE = 32;
    private static final String BASE64_PREFIX = "base64://";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final int maxBytes;
    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public ImageDataLoader(@Value("${humanoid.ai.max-image-bytes:10485760}") int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public Optional<String> toDataUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String cached = cache.get(url);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (url.startsWith("data:")) {
            return Optional.of(url);
        }
        try {
            byte[] bytes;
            if (url.startsWith(BASE64_PREFIX)) {
                bytes = Base64.getDecoder().decode(url.substring(BASE64_PREFIX.length()));
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    log.warn("图片下载失败 status={} url={}", response.statusCode(), url);
                    return Optional.empty();
                }
                bytes = response.body();
            } else {
                log.warn("不支持的图片来源: {}", url);
                return Optional.empty();
            }

            if (bytes.length == 0 || bytes.length > maxBytes) {
                log.warn("图片为空或过大 size={} url={}", bytes.length, url);
                return Optional.empty();
            }
            String dataUrl = "data:" + detectMime(bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes);
            cache.put(url, dataUrl);
            return Optional.of(dataUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("图片处理失败 url={}: {}", url, e.toString());
            return Optional.empty();
        }
    }

    public static String detectMime(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
