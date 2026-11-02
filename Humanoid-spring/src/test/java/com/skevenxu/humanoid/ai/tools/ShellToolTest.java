package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.FileSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellToolTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearContext() {
        ToolContext.clear();
    }

    private FileSandbox sandbox() {
        return new FileSandbox(dir.toString(), dir.resolve("skills").toString());
    }

    private void asUser(long userId) {
        ToolContext.set(new ToolContext.ChatScope(userId, "private", null));
    }

    @Test
    void runsSimpleCommand() {
        asUser(10001L);
        ShellTool tool = new ShellTool(sandbox(), true, 5, 1000);
        assertThat(tool.runShell("echo hello")).contains("hello");
    }

    @Test
    void blocksDangerousCommands() {
        asUser(10001L);
        ShellTool tool = new ShellTool(sandbox(), true, 5, 1000);
        assertThat(tool.runShell("rm -rf /")).contains("拒绝");
        assertThat(tool.runShell("shutdown -h now")).contains("拒绝");
        assertThat(tool.runShell("mkfs.ext4 /dev/sda1")).contains("拒绝");
    }

    @Test
    void canBeDisabled() {
        asUser(10001L);
        ShellTool tool = new ShellTool(sandbox(), false, 5, 1000);
        assertThat(tool.runShell("echo hello")).contains("禁用");
    }
}
