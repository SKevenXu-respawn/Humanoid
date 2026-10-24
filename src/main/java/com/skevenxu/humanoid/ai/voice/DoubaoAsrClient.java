package com.skevenxu.humanoid.ai.voice;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Optional;
import java.util.UUID;

/**
 * 豆包录音文件识别（极速版，同步返回）：把语音转成文字。
 * https://docs.volcengine.com/docs/6561/2608628
 */
@Component
public class DoubaoAsrClient {

    private static final Logger log = LoggerFactory.getLogger(DoubaoAsrClient.class);
    private static final Gson GSON = new Gson();
    private static final String URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";
    private static final int SUCCESS_CODE = 20000000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String resourceId;
    private final String model;
    private final boolean enabled;
    private final long timeoutSeconds;
    private final int maxAudioBytes;

    public DoubaoAsrClient(@Value("${humanoid.asr.api-key:${VOICE_API_KEY:}}") String apiKey,
                           @Value("${humanoid.asr.resource-id:volc.bigasr.auc_turbo}") String resourceId,
                           @Value("${humanoid.asr.model:bigmodel}") String model,
                           @Value("${humanoid.asr.enabled:true}") boolean enabled,
                           @Value("${humanoid.asr.timeout-seconds:30}") long timeoutSeconds,
                           @Value("${humanoid.asr.max-audio-bytes:10485760}") int maxAudioBytes) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.resourceId = resourceId;
        this.model = model;
        this.enabled = enabled;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.maxAudioBytes = maxAudioBytes;
        log.info("语音识别: enabled={} resource_id={} key={}",
                enabled, resourceId, this.apiKey.isBlank() ? "missing" : "configured");
    }

    public boolean isEnabled() {
        return enabled && !apiKey.isBlank();
    }

    /** 音频字节 -> 识别文本；失败或未启用时返回空。 */
    public Optional<String> transcribe(byte[] audio, String format) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (audio == null || audio.length == 0) {
            return Optional.empty();
        }
        if (audio.length > maxAudioBytes) {
            log.warn("语音文件过大，跳过识别 bytes={}", audio.length);
            return Optional.empty();
        }

        JsonObject audioParams = new JsonObject();
        audioParams.addProperty("data", Base64.getEncoder().encodeToString(audio));
        audioParams.addProperty("format", format == null || format.isBlank() ? "mp3" : format);

        JsonObject request = new JsonObject();
        request.addProperty("model_name", model);
        request.addProperty("enable_itn", true);
        request.addProperty("enable_punc", true);
        request.addProperty("enable_ddc", true);

        JsonObject body = new JsonObject();
        body.add("audio", audioParams);
        body.add("request", request);

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Request-Id", UUID.randomUUID().toString())
                    .header("X-Api-Sequence", "-1")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            JsonObject json = parse(response.body());
            JsonObject header = object(json, "header");
            int code = intValue(header, "code");
            if (response.statusCode() != 200 || (code != -1 && code != SUCCESS_CODE)) {
                log.warn("语音识别失败 status={} code={} message={} body={}",
                        response.statusCode(), code, string(header, "message"), abbreviate(response.body()));
                return Optional.empty();
            }

            JsonObject result = object(json, "result");
            String text = string(result, "text");
            if (text == null || text.isBlank()) {
                log.warn("语音识别没有返回文本 body={}", abbreviate(response.body()));
                return Optional.empty();
            }
            return Optional.of(text.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("语音识别被中断");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("语音识别异常: {}", e.toString());
            return Optional.empty();
        }
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement element = GSON.fromJson(body, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject object(JsonObject source, String key) {
        if (source == null) {
            return new JsonObject();
        }
        JsonElement element = source.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject source, String key) {
        if (source == null) {
            return null;
        }
        JsonElement element = source.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static int intValue(JsonObject source, String key) {
        if (source == null) {
            return -1;
        }
        JsonElement element = source.get(key);
        if (element == null || element.isJsonNull()) {
            return -1;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
