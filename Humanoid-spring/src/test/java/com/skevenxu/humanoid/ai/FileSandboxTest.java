package com.skevenxu.humanoid.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSandboxTest {

    @TempDir
    Path base;

    @Test
    void isolatesUsersAndAllowsSharedDirs() throws Exception {
        FileSandbox sandbox = new FileSandbox(base.toString(), base.resolve("skills").toString());

        Path user1 = sandbox.userDir(10001L);
        Path user2 = sandbox.userDir(10002L);
        assertThat(user1).isNotEqualTo(user2);

        Files.writeString(user1.resolve("secret.txt"), "user1 only");

        // 相对路径落在各自用户目录
        assertThat(sandbox.resolve(10001L, "secret.txt")).isEqualTo(user1.resolve("secret.txt"));
        assertThat(sandbox.resolve(10002L, "secret.txt")).isEqualTo(user2.resolve("secret.txt"));

        // 绝对路径越权被拒绝
        assertThatThrownBy(() -> sandbox.resolve(10002L, user1.resolve("secret.txt").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许");
        assertThatThrownBy(() -> sandbox.resolve(10001L, "../10002/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许");

        // skills/ 与 tools/ 共享可访问
        assertThat(sandbox.resolve(10001L, "skills/demo.md"))
                .isEqualTo(base.resolve("skills").resolve("demo.md"));
        assertThat(sandbox.resolve(10002L, "skills/demo.md"))
                .isEqualTo(base.resolve("skills").resolve("demo.md"));
        assertThat(sandbox.resolve(10001L, "tools/run.sh"))
                .isEqualTo(base.resolve("tools").resolve("run.sh"));
    }

    @Test
    void downloadsDirIsPerUser() {
        FileSandbox sandbox = new FileSandbox(base.toString(), base.resolve("skills").toString());
        assertThat(sandbox.downloadsDir(10001L)).isEqualTo(sandbox.userDir(10001L).resolve("downloads"));
        assertThat(sandbox.downloadsDir(10002L)).isEqualTo(sandbox.userDir(10002L).resolve("downloads"));
    }
}
