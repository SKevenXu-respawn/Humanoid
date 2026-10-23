package com.skevenxu.humanoid.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 技能库：Markdown + YAML frontmatter（name / description + 正文）。
 * 每次调用都重新读磁盘，因此 registerSkill 新建的技能立即可用。
 */
@Component
public class HumanoidSkillsTool {

    private static final Logger log = LoggerFactory.getLogger(HumanoidSkillsTool.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final int MAX_DESCRIPTION = 300;

    private final Path skillsDir;
    private final int maxContentChars;

    public HumanoidSkillsTool(
            @Value("${humanoid.tools.skills-dir:${humanoid.tools.file-base-dir:/app/files}/skills}") String skillsDir,
            @Value("${humanoid.tools.max-skill-chars:20000}") int maxContentChars) {
        this.skillsDir = Paths.get(skillsDir).toAbsolutePath().normalize();
        this.maxContentChars = Math.max(500, maxContentChars);
        try {
            Files.createDirectories(this.skillsDir);
        } catch (IOException e) {
            log.warn("无法创建技能目录 {}: {}", this.skillsDir, e.toString());
        }
    }

    @Tool(description = "列出当前所有可用技能的名称和描述。需要专门流程或领域知识时先看看有没有对应技能。")
    public String listSkills() {
        List<Skill> skills = loadAll();
        if (skills.isEmpty()) {
            return "当前没有已注册的技能";
        }
        StringBuilder sb = new StringBuilder("可用技能（共 " + skills.size() + " 个）：\n");
        for (Skill skill : skills) {
            sb.append("- ").append(skill.name()).append("：").append(skill.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "加载指定技能的完整说明。name 必须来自 listSkills 返回的技能名。")
    public String useSkill(@ToolParam(description = "技能名称，例如 pdf-tools") String name) {
        if (name == null || name.isBlank()) {
            return "错误：技能名不能为空";
        }
        String target = name.trim();
        Optional<Skill> skill = loadAll().stream()
                .filter(s -> s.name().equalsIgnoreCase(target))
                .findFirst();
        if (skill.isEmpty()) {
            return "未找到技能 " + target + "，请先调用 listSkills 查看可用技能";
        }
        log.info("工具调用 useSkill name={}", skill.get().name());
        return "技能：" + skill.get().name() + "\n说明：" + skill.get().description()
                + "\n\n" + skill.get().content();
    }

    @Tool(description = """
            创建或更新一个技能。name 为技能名（字母/数字/下划线/短横线），
            description 为一句话描述，content 为该技能的完整操作步骤或领域知识。
            创建后会立即出现在 listSkills 中，可被 useSkill 加载。""")
    public String registerSkill(@ToolParam(description = "技能名，例如 pdf-tools") String name,
                                @ToolParam(description = "一句话描述技能用途") String description,
                                @ToolParam(description = "技能正文：步骤、规则、示例等") String content) {
        if (name == null || !NAME_PATTERN.matcher(name.trim()).matches()) {
            return "错误：技能名只能包含字母、数字、下划线、短横线，长度 1-64";
        }
        if (description == null || description.isBlank()) {
            return "错误：description 不能为空";
        }
        if (description.length() > MAX_DESCRIPTION) {
            return "错误：description 过长（最多 " + MAX_DESCRIPTION + " 字）";
        }
        if (content == null || content.isBlank()) {
            return "错误：content 不能为空";
        }
        if (content.length() > maxContentChars) {
            return "错误：content 过长（最多 " + maxContentChars + " 字）";
        }

        String skillName = name.trim();
        String fileName = skillName + ".md";
        Path target = skillsDir.resolve(fileName).normalize();
        if (!target.startsWith(skillsDir)) {
            return "错误：非法的技能名";
        }

        String body = "---\n"
                + "name: " + skillName + "\n"
                + "description: " + description.trim().replaceAll("[\\r\\n]+", " ") + "\n"
                + "---\n\n"
                + content.trim() + "\n";
        try {
            Files.createDirectories(skillsDir);
            boolean exists = Files.exists(target);
            Files.writeString(target, body, StandardCharsets.UTF_8);
            log.info("工具调用 registerSkill name={} overwrite={}", skillName, exists);
            return (exists ? "已更新技能 " : "已注册技能 ") + skillName
                    + "，现在可以调用 useSkill 加载它。";
        } catch (IOException e) {
            log.warn("技能写入失败 name={}: {}", skillName, e.toString());
            return "错误：技能保存失败 " + e.getMessage();
        }
    }

    /** 供系统提示词使用：技能清单摘要，没有技能时返回空字符串。 */
    public String skillsBrief() {
        List<Skill> skills = loadAll();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(skills.size(), 30);
        for (int i = 0; i < limit; i++) {
            sb.append("- ").append(skills.get(i).name())
                    .append("：").append(skills.get(i).description()).append('\n');
        }
        if (skills.size() > limit) {
            sb.append("... 共 ").append(skills.size()).append(" 个，完整列表用 listSkills 查看\n");
        }
        return sb.toString().stripTrailing();
    }

    private record Skill(String name, String description, String content) {
    }

    /** 每次都重新扫描目录，保证动态注册后立即可见。 */
    private List<Skill> loadAll() {
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            List<Path> files = stream
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                try {
                    skills.add(parse(file));
                } catch (Exception e) {
                    log.warn("技能解析失败 {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("技能目录读取失败 {}: {}", skillsDir, e.toString());
        }
        return skills;
    }

    private Skill parse(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String fallbackName = file.getFileName().toString().replaceFirst("\\.md$", "");
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                String header = raw.substring(3, end);
                String body = raw.substring(end + 4).trim();
                String name = fallbackName;
                String description = "";
                for (String line : header.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if ("name".equalsIgnoreCase(key) && !value.isBlank()) {
                        name = value;
                    } else if ("description".equalsIgnoreCase(key)) {
                        description = value;
                    }
                }
                return new Skill(name, description.isBlank() ? "（无描述）" : description, body);
            }
        }
        String body = raw.trim();
        String description = body.lines().findFirst().orElse("（无描述）");
        return new Skill(fallbackName, description, body);
    }
}
