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
import java.util.UUID;

/**
 * 火山引擎豆包语音（长文本 TTS）客户端：
 * 先提交任务，再轮询结果，最后下载音频。文档：
 * https://docs.volcengine.com/docs/DoubaoVoice/Tasksubmission
 * https://docs.volcengine.com/docs/DoubaoVoice/Resultquery
 */
@Component
public class DoubaoVoiceClient {

    private static final Logger log = LoggerFactory.getLogger(DoubaoVoiceClient.class);
    private static final Gson GSON = new Gson();
    private static final String SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/tts/submit";
    private static final String QUERY_URL = "https://openspeech.bytedance.com/api/v3/tts/query";
    private static final int SUCCESS_CODE = 20000000;
    private static final int STATUS_RUNNING = 1;
    private static final int STATUS_SUCCESS = 2;
    private static final int STATUS_FAILED = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String apiKey;
    private final String resourceId;
    private final String speaker;
    private final String format;
    private final int sampleRate;
    private final int maxChars;
    private final long pollIntervalMillis;
    private final long timeoutMillis;
    private final int maxAudioBytes;

    public DoubaoVoiceClient(@Value("${humanoid.voice.api-key:${VOICE_API_KEY:}}") String apiKey,
                             @Value("${humanoid.voice.resource-id:seed-tts-2.0}") String resourceId,
                             @Value("${humanoid.voice.speaker:zh_female_jiaochuannv_uranus_bigtts}") String speaker,
                             @Value("${humanoid.voice.format:mp3}") String format,
                             @Value("${humanoid.voice.sample-rate:24000}") int sampleRate,
                             @Value("${humanoid.voice.max-chars:1000}") int maxChars,
                             @Value("${humanoid.voice.poll-interval-ms:1000}") long pollIntervalMillis,
                             @Value("${humanoid.voice.timeout-seconds:90}") long timeoutSeconds,
                             @Value("${humanoid.voice.max-audio-bytes:10485760}") int maxAudioBytes) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.resourceId = resourceId;
        this.speaker = speaker;
        this.format = format;
        this.sampleRate = sampleRate;
        this.maxChars = maxChars;
        this.pollIntervalMillis = Math.max(200, pollIntervalMillis);
        this.timeoutMillis = Math.max(5, timeoutSeconds) * 1000;
        this.maxAudioBytes = maxAudioBytes;
        if (this.apiKey.isBlank()) {
            log.warn("未配置语音合成 Key，请设置环境变量 VOICE_API_KEY");
        }
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** 合成语音并返回音频字节（默认 mp3）。 */
    public byte[] synthesize(String text) {
        return synthesize(text, null);
    }

    /** 使用指定音色合成；speaker 为空时使用配置默认音色。 */
    public byte[] synthesize(String text, String speakerOverride) {
        if (!isConfigured()) {
            throw new IllegalStateException("未配置语音合成 Key（VOICE_API_KEY）");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("要合成的文本不能为空");
        }
        String content = text.trim();
        if (content.length() > maxChars) {
            throw new IllegalArgumentException("文本过长，最多 " + maxChars + " 字");
        }
        String voice = speakerOverride == null || speakerOverride.isBlank()
                ? speaker : speakerOverride.trim();

        long started = System.currentTimeMillis();
        String taskId = submit(content, voice);
        String audioUrl = poll(taskId);
        byte[] audio = download(audioUrl);
        log.info("语音合成完成 task_id={} speaker={} chars={} bytes={} elapsed={}ms",
                taskId, voice, content.length(), audio.length, System.currentTimeMillis() - started);
        return audio;
    }

    private String submit(String text, String voice) {
        JsonObject uid = new JsonObject();
        uid.addProperty("uid", UUID.randomUUID().toString());

        JsonObject audioParams = new JsonObject();
        audioParams.addProperty("format", format);
        audioParams.addProperty("sample_rate", sampleRate);
        audioParams.addProperty("enable_timestamp", false);

        JsonObject reqParams = new JsonObject();
        reqParams.addProperty("text", text);
        reqParams.addProperty("speaker", voice);
        reqParams.add("audio_params", audioParams);
        reqParams.addProperty("additions", "{}");

        JsonObject body = new JsonObject();
        body.add("user", uid);
        body.addProperty("unique_id", UUID.randomUUID().toString());
        body.add("req_params", reqParams);

        JsonObject response = post(SUBMIT_URL, body);
        JsonObject data = object(response, "data");
        String taskId = string(data, "task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("语音任务提交成功但没有返回 task_id");
        }
        log.info("语音任务已提交 task_id={} task_status={}", taskId, intValue(data, "task_status"));
        return taskId;
    }

    private String poll(String taskId) {
        JsonObject body = new JsonObject();
        body.addProperty("task_id", taskId);

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            JsonObject response = post(QUERY_URL, body);
            JsonObject data = object(response, "data");
            int status = intValue(data, "task_status");
            if (status == STATUS_SUCCESS) {
                String url = string(data, "audio_url");
                if (url == null || url.isBlank()) {
                    throw new IllegalStateException("语音合成成功但没有返回 audio_url");
                }
                return url;
            }
            if (status == STATUS_FAILED) {
                throw new IllegalStateException("语音合成失败 task_id=" + taskId);
            }
            if (status != STATUS_RUNNING) {
                log.warn("未知语音任务状态 task_id={} status={}", taskId, status);
            }
            sleep();
        }
        throw new IllegalStateException("语音合成超时 task_id=" + taskId);
    }

    private byte[] download(String audioUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(audioUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                throw new IllegalStateException("音频下载失败 HTTP " + response.statusCode());
            }
            if (response.body().length > maxAudioBytes) {
                throw new IllegalStateException("音频过大: " + response.body().length + " 字节");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("音频下载被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("音频下载异常: " + e.getMessage(), e);
        }
    }

    private JsonObject post(String url, JsonObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Request-Id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject json = parse(response.body());
            if (response.statusCode() != 200 || json == null) {
                throw new IllegalStateException("语音服务 HTTP " + response.statusCode()
                        + "：" + abbreviate(response.body()));
            }
            int code = intValue(json, "code");
            if (code != SUCCESS_CODE) {
                String message = string(json, "message");
                throw new IllegalStateException("语音服务返回 code=" + code + "：" + message);
            }
            return json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("语音服务调用被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("语音服务调用异常: " + e.getMessage(), e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("语音合成被中断", e);
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
