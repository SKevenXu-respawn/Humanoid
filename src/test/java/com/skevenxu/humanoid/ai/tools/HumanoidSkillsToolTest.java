package com.skevenxu.humanoid.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HumanoidSkillsToolTest {

    @TempDir
    Path dir;

    @Test
    void registerAndUseSkillDynamically() throws Exception {
        HumanoidSkillsTool tool = new HumanoidSkillsTool(dir.toString(), 5000);

        assertThat(tool.listSkills()).contains("没有");

        String result = tool.registerSkill("pdf-tools", "处理 PDF 文件", "第一步：读取文件\n第二步：提取文本");
        assertThat(result).contains("已注册技能 pdf-tools");
        assertThat(Files.exists(dir.resolve("pdf-tools.md"))).isTrue();

        assertThat(tool.listSkills()).contains("pdf-tools").contains("处理 PDF 文件");
        assertThat(tool.useSkill("pdf-tools")).contains("第一步：读取文件");
        assertThat(tool.useSkill("PDF-TOOLS")).contains("第一步：读取文件");
        assertThat(tool.skillsBrief()).contains("pdf-tools");
    }

    @Test
    void newInstanceSeesRegisteredSkill() {
        HumanoidSkillsTool first = new HumanoidSkillsTool(dir.toString(), 5000);
        first.registerSkill("weekly-report", "写周报", "收集数据，再写初稿");

        HumanoidSkillsTool second = new HumanoidSkillsTool(dir.toString(), 5000);
        assertThat(second.listSkills()).contains("weekly-report");
        assertThat(second.useSkill("weekly-report")).contains("收集数据");
    }

    @Test
    void rejectsInvalidNames() {
        HumanoidSkillsTool tool = new HumanoidSkillsTool(dir.toString(), 5000);
        assertThat(tool.registerSkill("../evil", "x", "y")).contains("错误");
        assertThat(tool.registerSkill("a b", "x", "y")).contains("错误");
        assertThat(tool.useSkill("../evil")).contains("未找到");
    }
}
