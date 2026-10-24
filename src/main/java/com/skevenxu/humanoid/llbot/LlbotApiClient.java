package com.skevenxu.humanoid.llbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 调用 llbot 的 OneBot 11 HTTP API 发送消息。 */
@Component
public class LlbotApiClient {

    private static final Logger log = LoggerFactory.getLogger(LlbotApiClient.class);
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String token;

    public LlbotApiClient(@Value("${llbot.api-url:http://llbot:3000}") String baseUrl,
                          @Value("${llbot.api-token:}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public Optional<Long> sendPrivate(Long userId, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);
        body.addProperty("message", message);
        return call("send_private_msg", body);
    }

    public Optional<Long> sendGroup(Long groupId, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("group_id", groupId);
        body.addProperty("message", message);
        return call("send_group_msg", body);
    }

    /** 发送消息段数组（图片、表情等）。 */
    public Optional<Long> sendPrivateSegments(Long userId, JsonArray segments) {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);
        body.add("message", segments);
        return call("send_private_msg", body);
    }

    public Optional<Long> sendGroupSegments(Long groupId, JsonArray segments) {
        JsonObject body = new JsonObject();
        body.addProperty("group_id", groupId);
        body.add("message", segments);
        return call("send_group_msg", body);
    }

    /** 获取当前账号的收藏表情图片链接。 */
    public List<String> fetchCustomFaces() {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/fetch_custom_face"))
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            if (response.statusCode() != 200 || result == null
                    || result.get("retcode") == null || result.get("retcode").getAsInt() != 0) {
                log.error("llbot fetch_custom_face 调用失败 status={} body={}", response.statusCode(), response.body());
                return List.of();
            }
            JsonElement data = result.get("data");
            if (data == null || !data.isJsonArray()) {
                return List.of();
            }
            List<String> urls = new ArrayList<>();
            for (JsonElement element : data.getAsJsonArray()) {
                if (element != null && !element.isJsonNull()) {
                    urls.add(element.getAsString());
                }
            }
            return urls;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.error("llbot fetch_custom_face 调用异常", e);
            return List.of();
        }
    }

    /** 上传私聊文件，返回 file_id。 */
    public Optional<String> uploadPrivateFile(Long userId, String file, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);
        body.addProperty("file", file);
        if (name != null && !name.isBlank()) {
            body.addProperty("name", name);
        }
        return callForFileId("upload_private_file", body);
    }

    /** 上传群文件，返回 file_id。 */
    public Optional<String> uploadGroupFile(Long groupId, String file, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("group_id", groupId);
        body.addProperty("file", file);
        if (name != null && !name.isBlank()) {
            body.addProperty("name", name);
        }
        return callForFileId("upload_group_file", body);
    }

    /** 查询 LLBot / OneBot 运行状态，失败时返回空。 */
    public Optional<JsonObject> getStatus() {
        return request("get_status", new JsonObject(), Duration.ofSeconds(3));
    }

    /** 查询 LLBot 版本信息，失败时返回空。 */
    public Optional<JsonObject> getVersion() {
        return request("get_version", new JsonObject(), Duration.ofSeconds(3));
    }

    /** 获取好友列表。 */
    public Optional<List<JsonObject>> getFriendList() {
        return requestData("get_friend_list", new JsonObject(), Duration.ofSeconds(10)).map(data -> {
            List<JsonObject> friends = new ArrayList<>();
            if (data.isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        friends.add(element.getAsJsonObject());
                    }
                }
            }
            return friends;
        });
    }

    /** 处理好友申请；flag 来自好友申请事件。 */
    public boolean setFriendAddRequest(String flag, boolean approve, String remark) {
        JsonObject body = new JsonObject();
        body.addProperty("flag", flag);
        body.addProperty("approve", approve);
        body.addProperty("remark", remark == null ? "" : remark);
        return requestData("set_friend_add_request", body, Duration.ofSeconds(10)).isPresent();
    }

    /** 删除好友。 */
    public boolean deleteFriend(long userId) {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId);
        return requestData("delete_friend", body, Duration.ofSeconds(10)).isPresent();
    }

    /** 获取被 QQ 过滤掉的好友申请。 */
    public Optional<List<JsonObject>> getDoubtFriendRequests(int count) {
        JsonObject body = new JsonObject();
        body.addProperty("count", Math.max(1, Math.min(count, 200)));
        return requestData("get_doubt_friends_add_request", body, Duration.ofSeconds(10)).map(data -> {
            List<JsonObject> requests = new ArrayList<>();
            if (data.isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        requests.add(element.getAsJsonObject());
                    }
                }
            }
            return requests;
        });
    }

    /** 处理被过滤的好友申请（该接口只支持同意）。 */
    public boolean setDoubtFriendAddRequest(String flag) {
        JsonObject body = new JsonObject();
        body.addProperty("flag", flag);
        return requestData("set_doubt_friends_add_request", body, Duration.ofSeconds(10)).isPresent();
    }

    /** 获取被引用消息的文本摘要，格式：昵称(QQ号): 内容。 */
    public Optional<String> getMessageText(long messageId) {
        JsonObject body = new JsonObject();
        body.addProperty("message_id", messageId);
        return request("get_msg", body, Duration.ofSeconds(6)).flatMap(data -> {
            String text = data.has("message") && data.get("message").isJsonArray()
                    ? extractText(data.getAsJsonArray("message"))
                    : "";
            if (text.isBlank() && data.has("raw_message") && !data.get("raw_message").isJsonNull()) {
                text = data.get("raw_message").getAsString();
            }
            boolean deleted = data.has("status") && !data.get("status").isJsonNull()
                    && "deleted".equals(data.get("status").getAsString());
            if (text.isBlank()) {
                return Optional.empty();
            }
            if (deleted) {
                text = "[已撤回] " + text;
            }

            String sender = "";
            if (data.has("sender") && data.get("sender").isJsonObject()) {
                JsonObject s = data.getAsJsonObject("sender");
                String card = s.has("card") && !s.get("card").isJsonNull() ? s.get("card").getAsString() : "";
                String nickname = s.has("nickname") && !s.get("nickname").isJsonNull()
                        ? s.get("nickname").getAsString() : "";
                String uid = s.has("user_id") && !s.get("user_id").isJsonNull()
                        ? s.get("user_id").getAsString() : "";
                String name = !card.isBlank() ? card : nickname;
                sender = name.isBlank() ? uid : (uid.isBlank() ? name : name + "(" + uid + ")");
            }
            return Optional.of((sender.isBlank() ? "" : sender + ": ") + text);
        });
    }

    /** 获取语音消息音频内容，返回 base64（默认转成 mp3）。 */
    public Optional<String> getRecordBase64(String file) {
        JsonObject body = new JsonObject();
        body.addProperty("file", file);
        body.addProperty("out_format", "mp3");
        return request("get_record", body, Duration.ofSeconds(30))
                .map(data -> {
                    JsonElement base64 = data.get("base64");
                    return base64 == null || base64.isJsonNull() ? null : base64.getAsString();
                })
                .filter(value -> value != null && !value.isBlank());
    }

    /** LLBot 自带的语音转文字（依赖 QQ 能力），作为识别失败时的兜底。 */
    public Optional<String> voiceMsgToText(long messageId) {
        JsonObject body = new JsonObject();
        body.addProperty("message_id", messageId);
        return request("voice_msg_to_text", body, Duration.ofSeconds(20))
                .map(data -> {
                    JsonElement text = data.get("text");
                    return text == null || text.isJsonNull() ? null : text.getAsString();
                })
                .filter(value -> value != null && !value.isBlank());
    }

    private static String extractText(JsonArray segments) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : segments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            String type = segment.has("type") ? segment.get("type").getAsString() : "";
            JsonElement dataElement = segment.get("data");
            JsonObject data = dataElement != null && dataElement.isJsonObject()
                    ? dataElement.getAsJsonObject() : new JsonObject();
            switch (type) {
                case "text" -> sb.append(data.has("text") ? data.get("text").getAsString() : "");
                case "image" -> sb.append("[图片]");
                case "face" -> sb.append("[表情]");
                default -> {
                }
            }
        }
        return sb.toString().trim();
    }

    private Optional<Long> call(String action, JsonObject body) {
        return request(action, body, Duration.ofSeconds(15))
                .flatMap(data -> data.has("message_id")
                        ? Optional.of(data.get("message_id").getAsLong())
                        : Optional.empty());
    }

    private Optional<String> callForFileId(String action, JsonObject body) {
        return request(action, body, Duration.ofSeconds(120))
                .map(data -> data.has("file_id") ? data.get("file_id").getAsString() : "ok");
    }

    private Optional<JsonObject> request(String action, JsonObject body, Duration timeout) {
        return requestData(action, body, timeout)
                .map(data -> data.isJsonObject() ? data.getAsJsonObject() : new JsonObject());
    }

    /** 调用 OneBot action；retcode=0 时返回 data（可能为 JsonNull）。 */
    private Optional<JsonElement> requestData(String action, JsonObject body, Duration timeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + action))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
            if (response.statusCode() != 200 || result == null
                    || result.get("retcode") == null || result.get("retcode").getAsInt() != 0) {
                log.error("llbot {} 调用失败 status={} body={}", action, response.statusCode(), response.body());
                return Optional.empty();
            }
            JsonElement data = result.get("data");
            return Optional.of(data == null ? JsonNull.INSTANCE : data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("llbot {} 调用被中断", action, e);
            return Optional.empty();
        } catch (Exception e) {
            log.error("llbot {} 调用异常", action, e);
            return Optional.empty();
        }
    }
}
