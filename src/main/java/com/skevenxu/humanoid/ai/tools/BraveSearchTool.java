package com.skevenxu.humanoid.ai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.ai.secret.SecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Brave 网页搜索，密钥从管理端配置读取，修改后立即生效。 */
@Component
public class BraveSearchTool {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchTool.class);
    private static final Gson GSON = new Gson();
    private static final String URL = "https://api.search.brave.com/res/v1/web/search";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final SecretService secretService;

    public BraveSearchTool(SecretService secretService) {
        this.secretService = secretService;
    }

    @Tool(name = "BraveWebSearch",
            description = "使用 Brave 搜索网页，返回标题、链接和摘要。需要最新信息或不确定答案时使用。")
    public String search(@ToolParam(description = "搜索关键词") String query) {
        if (query == null || query.isBlank()) {
            return "错误：搜索关键词不能为空";
        }
        String key = secretService.braveKey();
        if (key.isBlank()) {
            return "错误：未配置 Brave Search API Key，请在管理端密钥配置中设置";
        }

        try {
            String url = URL + "?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + "&count=5";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", key)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Brave 搜索失败 status={} body={}",
                        response.statusCode(), abbreviate(response.body()));
                return "搜索失败：HTTP " + response.statusCode();
            }
            return format(query, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "搜索被中断";
        } catch (Exception e) {
            log.warn("Brave 搜索异常: {}", e.toString());
            return "搜索失败：" + e.getMessage();
        }
    }

    private static String format(String query, String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        JsonObject web = json != null && json.has("web") && json.get("web").isJsonObject()
                ? json.getAsJsonObject("web") : null;
        JsonElement resultsElement = web == null ? null : web.get("results");
        if (resultsElement == null || !resultsElement.isJsonArray()) {
            return "没有找到与「" + query.trim() + "」相关的结果";
        }
        JsonArray results = resultsElement.getAsJsonArray();
        StringBuilder sb = new StringBuilder("搜索「").append(query.trim()).append("」的结果：\n");
        int count = 0;
        for (JsonElement element : results) {
            if (!element.isJsonObject() || count >= 5) {
                break;
            }
            JsonObject item = element.getAsJsonObject();
            String title = str(item, "title");
            String url = str(item, "url");
            String description = str(item, "description");
            if (url == null || url.isBlank()) {
                continue;
            }
            count++;
            sb.append(count).append(". ").append(title == null ? url : title).append("\n")
                    .append(url).append("\n");
            if (description != null && !description.isBlank()) {
                sb.append(abbreviate(description)).append("\n");
            }
        }
        if (count == 0) {
            return "没有找到与「" + query.trim() + "」相关的结果";
        }
        return sb.toString().stripTrailing();
    }

    private static String str(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
