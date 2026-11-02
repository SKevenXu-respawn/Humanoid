package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.FileSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadToolTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearContext() {
        ToolContext.clear();
    }

    @Test
    void blocksInternalAddressesAndNonHttpSchemes() {
        FileSandbox sandbox = new FileSandbox(dir.toString(), dir.resolve("skills").toString());
        DownloadTool tool = new DownloadTool(sandbox, 1024 * 1024);
        ToolContext.set(new ToolContext.ChatScope(10001L, "private", null));

        assertThat(tool.downloadFile("http://127.0.0.1:8080/a", null)).contains("禁止");
        assertThat(tool.downloadFile("http://10.0.0.1/a", null)).contains("禁止");
        assertThat(tool.downloadFile("http://192.168.1.1/a", null)).contains("禁止");
        assertThat(tool.downloadFile("http://169.254.169.254/latest/meta-data", null)).contains("禁止");
        assertThat(tool.downloadFile("file:///etc/passwd", null)).contains("http");
    }
}
