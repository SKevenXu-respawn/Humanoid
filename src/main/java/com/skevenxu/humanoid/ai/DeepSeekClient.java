package com.skevenxu.humanoid.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * DeepSeek Chat Completions 原生客户端。
 * Spring AI 2.x 的 DeepSeek 集成只支持纯文本 content，图片需要多模态 content 数组，因此直接调用 HTTP。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_TOOL_RESULT = 6000;
    /** 配置为 -1（不限制）时的硬保护，避免异常循环无限消耗资源。 */
    private static final int HARD_MAX_ROUNDS = 1000;

    public record ChatMessage(String role, String text, List<String> imageUrls) {
        public static ChatMessage user(String text, List<String> imageUrls) {
            return new ChatMessage("user", text, imageUrls);
        }

        public static ChatMessage assistant(String text) {
            return new ChatMessage("assistant", text, List.of());
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ImageDataLoader imageDataLoader;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean thinkingEnabled;
    private final String reasoningEffort;
    private final int maxToolRounds;

    public DeepSeekClient(ImageDataLoader imageDataLoader,
                          @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                          @Value("${spring.ai.deepseek.api-key}") String apiKey,
                          @Value("${spring.ai.deepseek.chat.options.model:deepseek-flash}") String model,
                          @Value("${humanoid.ai.thinking:disabled}") String thinking,
                          @Value("${humanoid.ai.max-tool-rounds:25}") int maxToolRounds) {
        this.imageDataLoader = imageDataLoader;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxToolRounds = maxToolRounds;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 DeepSeek API Key，请设置环境变量 DEEPSEEK_API_KEY");
        }

        String level = thinking == null ? "disabled" : thinking.trim().toLowerCase();
        if ("disabled".equals(level)) {
            this.thinkingEnabled = false;
            this.reasoningEffort = null;
        } else if ("low".equals(level) || "high".equals(level) || "max".equals(level)) {
            this.thinkingEnabled = true;
            this.reasoningEffort = level;
        } else {
            this.thinkingEnabled = true;
            this.reasoningEffort = null;
        }
    }

    public Optional<String> chat(String systemPrompt, List<ChatMessage> messages) {
        return chat(systemPrompt, messages, List.of());
    }

    /** 带工具调用的对话；toolCallback 为空时退化为普通对话。 */
    public Optional<String> chat(String systemPrompt, List<ChatMessage> messages,
                                 List<ToolCallback> toolCallbacks) {
        try {
            JsonArray conversation = buildMessages(systemPrompt, messages);
            JsonArray tools = buildToolSchemas(toolCallbacks);

            int roundLimit = maxToolRounds <= 0
                    ? HARD_MAX_ROUNDS
                    : Math.min(maxToolRounds, HARD_MAX_ROUNDS);
            for (int round = 0; round < roundLimit; round++) {
                JsonObject body = buildBody(conversation, tools);

                HttpResponse<String> response = send(body);
                if (response.statusCode() != 200) {
                    log.error("DeepSeek 调用失败 status={} body={}", response.statusCode(),
                            response.body().length() > 500 ? response.body().substring(0, 500) : response.body());
                    return failToCaller(toolCallbacks,
                            "（AI 服务调用失败：HTTP " + response.statusCode() + "，请稍后重试）");
                }

                JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
                JsonArray choices = result == null ? null : result.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    log.error("DeepSeek 返回无 choices: {}", response.body());
                    return failToCaller(toolCallbacks, "（AI 服务未返回结果，请稍后重试）");
                }
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message == null) {
                    return failToCaller(toolCallbacks, "（AI 服务返回格式异常，请稍后重试）");
                }

                JsonArray toolCalls = message.has("tool_calls") && message.get("tool_calls").isJsonArray()
                        ? message.getAsJsonArray("tool_calls") : null;
                if (toolCalls == null || toolCalls.isEmpty() || toolCallbacks.isEmpty()) {
                    JsonElement content = message.get("content");
                    if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
                        return failToCaller(toolCallbacks, "（AI 没有返回内容，请稍后重试）");
                    }
                    return Optional.of(content.getAsString());
                }

                conversation.add(assistantToolCallMessage(message, toolCalls));
                for (JsonElement element : toolCalls) {
                    conversation.add(executeToolCall(element, toolCallbacks));
                }
            }

            log.warn("工具调用轮次超过上限 {}", roundLimit);
            Optional<String> finalAnswer = finalAnswerWithoutTools(conversation);
            return finalAnswer.isPresent()
                    ? finalAnswer
                    : failToCaller(toolCallbacks, "（工具调用达到上限，且未能生成最终回复，请稍后重试）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.error("DeepSeek 调用异常", e);
            return Optional.empty();
        }
    }

    private JsonObject buildBody(JsonArray conversation, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.add("messages", conversation);

        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", thinkingEnabled ? "enabled" : "disabled");
        body.add("thinking", thinking);
        if (reasoningEffort != null) {
            body.addProperty("reasoning_effort", reasoningEffort);
        }
        if (tools != null) {
            body.add("tools", tools);
        }
        return body;
    }

    /** 工具轮次用尽后，给模型补一条系统提示并强制让它给出最终回答（不再提供工具）。 */
    private Optional<String> finalAnswerWithoutTools(JsonArray conversation) {
        try {
            JsonObject notice = new JsonObject();
            notice.addProperty("role", "system");
            notice.addProperty("content",
                    "工具调用已达到上限。请停止调用工具，基于已有信息和工具结果直接回答用户；"
                            + "如果任务确实无法完成，请明确说明原因和当前进度。");
            conversation.add(notice);

            HttpResponse<String> response = send(buildBody(conversation, null));
            if (response.statusCode() != 200) {
                log.error("DeepSeek 最终回答调用失败 status={}", response.statusCode());
                return Optional.empty();
            }
            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray choices = result == null ? null : result.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Optional.empty();
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            JsonElement content = message == null ? null : message.get("content");
            return content == null || content.isJsonNull() || content.getAsString().isBlank()
                    ? Optional.empty()
                    : Optional.of(content.getAsString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.error("DeepSeek 最终回答调用异常", e);
            return Optional.empty();
        }
    }

    /** 有工具调用时把错误文本返回给调用方（最终会发给用户），普通调用保持空返回。 */
    private static Optional<String> failToCaller(List<ToolCallback> toolCallbacks, String message) {
        return toolCallbacks == null || toolCallbacks.isEmpty()
                ? Optional.empty()
                : Optional.of(message);
    }

    private HttpResponse<String> send(JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject assistantToolCallMessage(JsonObject message, JsonArray toolCalls) {
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        JsonElement content = message.get("content");
        assistant.addProperty("content", content == null || content.isJsonNull() ? "" : content.getAsString());
        assistant.add("tool_calls", toolCalls);
        return assistant;
    }

    private JsonObject executeToolCall(JsonElement element, List<ToolCallback> callbacks) {
        JsonObject call = element.getAsJsonObject();
        String id = call.has("id") ? call.get("id").getAsString() : "";
        JsonObject function = call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function") : new JsonObject();
        String name = function.has("name") ? function.get("name").getAsString() : "";
        String arguments = function.has("arguments") && !function.get("arguments").isJsonNull()
                ? function.get("arguments").getAsString() : "{}";

        String result;
        Optional<ToolCallback> callback = callbacks.stream()
                .filter(cb -> cb.getToolDefinition().name().equals(name))
                .findFirst();
        if (callback.isEmpty()) {
            result = "错误：未知工具 " + name;
        } else {
            try {
                result = callback.get().call(arguments);
            } catch (Exception e) {
                log.error("工具执行失败 name={}", name, e);
                result = "错误：工具执行失败 " + e.getMessage();
            }
        }
        if (result == null || result.isBlank()) {
            result = "错误：工具没有返回内容";
        }
        result = PromptSanitizer.truncate(PromptSanitizer.clean(result), MAX_TOOL_RESULT);

        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        toolMessage.addProperty("tool_call_id", id);
        toolMessage.addProperty("content", result);
        return toolMessage;
    }

    private static JsonArray buildToolSchemas(List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return null;
        }
        JsonArray tools = new JsonArray();
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            JsonObject function = new JsonObject();
            function.addProperty("name", definition.name());
            function.addProperty("description", definition.description());
            JsonElement schema;
            try {
                schema = JsonParser.parseString(definition.inputSchema());
            } catch (Exception e) {
                schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}");
            }
            function.add("parameters", schema);

            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private JsonArray buildMessages(String systemPrompt, List<ChatMessage> messages) {
        JsonArray array = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            array.add(system);
        }
        for (ChatMessage message : messages) {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", message.role());

            List<String> dataUrls = message.imageUrls() == null ? List.of()
                    : message.imageUrls().stream()
                    .map(imageDataLoader::toDataUrl)
                    .flatMap(Optional::stream)
                    .toList();

            if (!"user".equals(message.role()) || dataUrls.isEmpty()) {
                obj.addProperty("content", message.text() == null ? "" : message.text());
            } else {
                JsonArray parts = new JsonArray();
                if (message.text() != null && !message.text().isBlank()) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", message.text());
                    parts.add(textPart);
                }
                for (String dataUrl : dataUrls) {
                    JsonObject imagePart = new JsonObject();
                    imagePart.addProperty("type", "image_url");
                    JsonObject imageUrl = new JsonObject();
                    imageUrl.addProperty("url", dataUrl);
                    imagePart.add("image_url", imageUrl);
                    parts.add(imagePart);
                }
                obj.add("content", parts);
            }
            array.add(obj);
        }
        return array;
    }
}
