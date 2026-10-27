package com.skevenxu.humanoid.ai.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotUserPrompt;
import com.skevenxu.humanoid.mapper.BotUserPromptMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 按 QQ 绑定的自定义系统提示词；没有绑定时回退全局系统提示词。 */
@Service
public class UserPromptService {

    private final BotUserPromptMapper promptMapper;
    private final PromptService promptService;

    public UserPromptService(BotUserPromptMapper promptMapper, PromptService promptService) {
        this.promptMapper = promptMapper;
        this.promptService = promptService;
    }

    public String systemPromptFor(Long userId) {
        if (userId != null) {
            BotUserPrompt custom = find(userId);
            if (custom != null && StringUtils.hasText(custom.getContent())) {
                return custom.getContent();
            }
        }
        return promptService.systemPrompt();
    }

    public boolean hasCustom(Long userId) {
        BotUserPrompt custom = find(userId);
        return custom != null && StringUtils.hasText(custom.getContent());
    }

    public BotUserPrompt find(Long userId) {
        if (userId == null) {
            return null;
        }
        return promptMapper.selectOne(new LambdaQueryWrapper<BotUserPrompt>()
                .eq(BotUserPrompt::getUserId, userId));
    }
}
