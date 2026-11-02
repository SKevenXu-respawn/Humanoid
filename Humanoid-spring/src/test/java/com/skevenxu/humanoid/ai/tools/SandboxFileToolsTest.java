package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.FileSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxFileToolsTest {

    @TempDir
    Path base;

    @AfterEach
    void clearContext() {
        ToolContext.clear();
    }

    private void asUser(long userId) {
        ToolContext.set(new ToolContext.ChatScope(userId, "private", null));
    }

    @Test
    void writesAndReadsPerUser() {
        FileSandbox sandbox = new FileSandbox(base.toString(), base.resolve("skills").toString());
        SandboxFileTools tools = new SandboxFileTools(sandbox, 262144);

        asUser(10001L);
        assertThat(tools.writeFile("notes/a.txt", "hello one")).contains("已写入");
        assertThat(tools.readFile("notes/a.txt", null, null)).contains("hello one").contains("1\t");

        asUser(10002L);
        assertThat(tools.readFile("notes/a.txt", null, null)).startsWith("错误");
        assertThat(tools.writeFile("notes/a.txt", "hello two")).contains("已写入");
        assertThat(tools.readFile("notes/a.txt", null, null)).contains("hello two");

        asUser(10001L);
        assertThat(tools.readFile("notes/a.txt", null, null)).contains("hello one");
    }

    @Test
    void sharedDirsAreVisibleToAllUsers() throws Exception {
        FileSandbox sandbox = new FileSandbox(base.toString(), base.resolve("skills").toString());
        SandboxFileTools tools = new SandboxFileTools(sandbox, 262144);

        Files.createDirectories(sandbox.skillsDir());
        Files.writeString(sandbox.skillsDir().resolve("demo.md"), "# Demo skill");

        asUser(20001L);
        assertThat(tools.readFile("skills/demo.md", null, null)).contains("Demo skill");
        assertThat(tools.listDirectory("skills", 1, 50)).contains("demo.md");
    }

    @Test
    void blocksEscapeAndOtherUserFiles() {
        FileSandbox sandbox = new FileSandbox(base.toString(), base.resolve("skills").toString());
        SandboxFileTools tools = new SandboxFileTools(sandbox, 262144);

        asUser(10001L);
        assertThat(tools.writeFile("../10002/x.txt", "nope")).startsWith("错误");
        assertThat(tools.readFile("../10002/x.txt", null, null)).startsWith("错误");
    }

    @Test
    void toolsRequireUserContext() {
        FileSandbox sandbox = new FileSandbox(base.toString(), base.resolve("skills").toString());
        SandboxFileTools tools = new SandboxFileTools(sandbox, 262144);
        assertThat(tools.readFile("a.txt", null, null)).contains("无法确定当前用户");
    }
}
