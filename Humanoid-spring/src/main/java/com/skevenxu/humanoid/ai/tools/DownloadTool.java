package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.FileSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;

/** 网络文件下载：保存到沙箱 downloads 目录。 */
@Component
public class DownloadTool {

    private static final Logger log = LoggerFactory.getLogger(DownloadTool.class);
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final FileSandbox sandbox;
    private final long maxBytes;

    public DownloadTool(FileSandbox sandbox,
                        @Value("${humanoid.tools.max-download-bytes:52428800}") long maxBytes) {
        this.sandbox = sandbox;
        this.maxBytes = maxBytes;
    }

    @Tool(description = """
            从网络下载文件并保存到 downloads 目录。url 必须是 http/https 地址（禁止内网地址）。
            fileName 可选，缺省从 URL 推断。下载的是图片时，之后可以用 sendImage 发送该文件。
            返回保存的相对路径（downloads/xxx）和文件大小。""")
    public String downloadFile(@ToolParam(description = "要下载的 http/https URL") String url,
                               @ToolParam(description = "保存的文件名，可选", required = false) String fileName) {
        if (url == null || url.isBlank()) {
            return "错误：url 不能为空";
        }
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前用户";
        }
        try {
            URI uri = URI.create(url.trim());
            validateUrl(uri);

            HttpResponse<InputStream> response = fetchFollowingRedirects(uri, MAX_REDIRECTS);
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return "错误：下载失败，HTTP " + response.statusCode();
                }

                Path downloadsDir = sandbox.downloadsDir(scope.userId());
                Path userDir = sandbox.userDir(scope.userId());
                Path target = resolveTarget(fileName, uri, response.headers(), downloadsDir);
                long size = copyLimited(body, target);

                String relative = userDir.relativize(target).toString().replace('\\', '/');
                log.info("工具调用 downloadFile user_id={} url={} -> {} ({} bytes)",
                        scope.userId(), url, relative, size);
                return "已下载到 " + relative + "（" + size + " 字节）";
            }
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("下载失败 url={}: {}", url, e.toString());
            return "错误：下载失败 " + e.getMessage();
        }
    }

    private HttpResponse<InputStream> fetchFollowingRedirects(URI uri, int redirectsLeft)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "HumanoidBot/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status >= 300 && status < 400 && redirectsLeft > 0) {
            String location = response.headers().firstValue("Location").orElse(null);
            response.body().close();
            if (location == null || location.isBlank()) {
                throw new IllegalArgumentException("重定向缺少 Location");
            }
            URI next = uri.resolve(location);
            validateUrl(next);
            return fetchFollowingRedirects(next, redirectsLeft - 1);
        }
        return response;
    }

    /** 只允许 http/https，并阻止回环、内网、链路本地等地址（SSRF 防护）。 */
    private static void validateUrl(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("只支持 http/https 地址");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isBlocked(address)) {
                throw new IllegalArgumentException("禁止访问内网或保留地址");
            }
        }
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0 || first == 255) {
                return true;
            }
            // 100.64.0.0/10 (CGNAT)
            return first == 100 && second >= 64 && second <= 127;
        }
        // 注意：不拦截 IPv6 ULA（fc00::/7）和 198.18.0.0/15，
        // 部分代理（如 Clash fake-IP / TUN）会把公网域名解析到这些网段。
        return false;
    }

    private Path resolveTarget(String fileName, URI uri, HttpHeaders headers, Path downloadsDir) {
        String name;
        if (fileName != null && !fileName.isBlank()) {
            name = Paths.get(fileName).getFileName().toString();
        } else {
            String path = uri.getPath() == null ? "" : uri.getPath();
            Path fromUrl = Paths.get(path);
            name = fromUrl.getFileName() == null ? "" : fromUrl.getFileName().toString();
        }

        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            name = "download-" + System.currentTimeMillis();
        }
        if (!name.contains(".")) {
            String extension = extensionFor(headers.firstValue("Content-Type").orElse(""));
            if (!extension.isEmpty()) {
                name += extension;
            }
        }

        Path target = downloadsDir.resolve(name).normalize();
        if (!target.startsWith(downloadsDir)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        String base = name;
        int index = 1;
        while (Files.exists(target)) {
            int dot = base.lastIndexOf('.');
            String candidate = dot > 0
                    ? base.substring(0, dot) + "-" + index + base.substring(dot)
                    : base + "-" + index;
            target = downloadsDir.resolve(candidate);
            index++;
        }
        return target;
    }

    private static String extensionFor(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("image/jpeg")) return ".jpg";
        if (type.contains("image/png")) return ".png";
        if (type.contains("image/gif")) return ".gif";
        if (type.contains("image/webp")) return ".webp";
        if (type.contains("application/pdf")) return ".pdf";
        if (type.contains("application/zip")) return ".zip";
        if (type.contains("text/plain")) return ".txt";
        if (type.contains("application/json")) return ".json";
        return "";
    }

    private long copyLimited(InputStream input, Path target) throws Exception {
        long total = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    output.close();
                    Files.deleteIfExists(target);
                    throw new IllegalArgumentException("文件超过大小上限 " + maxBytes + " 字节");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }
}
