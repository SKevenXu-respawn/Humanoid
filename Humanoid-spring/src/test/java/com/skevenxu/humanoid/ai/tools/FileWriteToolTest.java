package com.skevenxu.humanoid.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileWriteToolTest {

    @TempDir
    Path tempDir;

    @Test
    void writesInsideSandboxAndBlocksTraversal() throws Exception {
        FileWriteTool tool = new FileWriteTool(tempDir.toString(), 1024);

        assertThat(tool.writeFile("sub/a.txt", "你好")).startsWith("已写入");
        assertThat(Files.readString(tempDir.resolve("sub/a.txt"))).isEqualTo("你好");

        assertThat(tool.writeFile("../outside.txt", "x")).startsWith("错误");
        assertThat(tool.writeFile(tempDir.getParent().resolve("abs.txt").toString(), "x")).startsWith("错误");
        assertThat(tool.writeFile(".", "x")).startsWith("错误");
        assertThat(tool.writeFile("big.txt", "x".repeat(2048))).startsWith("错误");
    }
}
