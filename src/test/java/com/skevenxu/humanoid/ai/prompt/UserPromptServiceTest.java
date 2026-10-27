package com.skevenxu.humanoid.ai.prompt;

import com.skevenxu.humanoid.entity.BotUserPrompt;
import com.skevenxu.humanoid.mapper.BotUserPromptMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPromptServiceTest {

    @Test
    void customPromptOverridesGlobal() {
        BotUserPromptMapper mapper = mock(BotUserPromptMapper.class);
        PromptService global = mock(PromptService.class);
        BotUserPrompt custom = new BotUserPrompt();
        custom.setUserId(10001L);
        custom.setContent("custom prompt");
        when(mapper.selectOne(any())).thenReturn(custom);

        UserPromptService service = new UserPromptService(mapper, global);

        assertThat(service.systemPromptFor(10001L)).isEqualTo("custom prompt");
        verify(global, never()).systemPrompt();
    }

    @Test
    void missingBindingFallsBackToGlobal() {
        BotUserPromptMapper mapper = mock(BotUserPromptMapper.class);
        PromptService global = mock(PromptService.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(global.systemPrompt()).thenReturn("global prompt");

        UserPromptService service = new UserPromptService(mapper, global);

        assertThat(service.systemPromptFor(10001L)).isEqualTo("global prompt");
    }

    @Test
    void blankCustomFallsBackToGlobal() {
        BotUserPromptMapper mapper = mock(BotUserPromptMapper.class);
        PromptService global = mock(PromptService.class);
        BotUserPrompt custom = new BotUserPrompt();
        custom.setUserId(10001L);
        custom.setContent("   ");
        when(mapper.selectOne(any())).thenReturn(custom);
        when(global.systemPrompt()).thenReturn("global prompt");

        UserPromptService service = new UserPromptService(mapper, global);

        assertThat(service.systemPromptFor(10001L)).isEqualTo("global prompt");
    }

    @Test
    void nullUserUsesGlobal() {
        BotUserPromptMapper mapper = mock(BotUserPromptMapper.class);
        PromptService global = mock(PromptService.class);
        when(global.systemPrompt()).thenReturn("global prompt");

        UserPromptService service = new UserPromptService(mapper, global);

        assertThat(service.systemPromptFor(null)).isEqualTo("global prompt");
    }
}
