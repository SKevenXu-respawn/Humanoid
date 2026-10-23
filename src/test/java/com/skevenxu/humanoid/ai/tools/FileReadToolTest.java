package com.skevenxu.humanoid.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileReadToolTest {

    @TempDir
    Path tempDir;

    @Test
    void readsInsideSandboxAndBlocksTraversal() throws Exception {
        Files.writeString(tempDir.resolve("note.txt"), "你好，世界");
        Path relativeOutside = Path.of("..", "outside-" + System.nanoTime() + ".txt").normalize();
        Path outside = tempDir.resolve(relativeOutside);
        Files.writeString(outside, "secret");

        FileReadTool tool = new FileReadTool(tempDir.toString(), 1024);

        assertThat(tool.readFile("note.txt")).isEqualTo("你好，世界");
        assertThat(tool.readFile("../" + outside.getFileName())).startsWith("错误");
        assertThat(tool.readFile(outside.toAbsolutePath().toString())).startsWith("错误");
    }
}
