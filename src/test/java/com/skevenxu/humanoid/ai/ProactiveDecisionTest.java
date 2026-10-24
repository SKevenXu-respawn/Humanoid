package com.skevenxu.humanoid.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveDecisionTest {

    @Test
    void parsesChat() {
        var decision = ProactiveService.parseDecision("CHAT: 在忙吗？想找你聊聊");
        assertThat(decision.type()).isEqualTo("CHAT");
        assertThat(decision.content()).isEqualTo("在忙吗？想找你聊聊");
    }

    @Test
    void parsesDoneAndSkip() {
        assertThat(ProactiveService.parseDecision("DONE: 看了一个猫视频并留言").type()).isEqualTo("DONE");
        assertThat(ProactiveService.parseDecision("SKIP").type()).isEqualTo("SKIP");
        assertThat(ProactiveService.parseDecision("随便说点什么").type()).isEqualTo("SKIP");
        assertThat(ProactiveService.parseDecision("CHAT:   ").type()).isEqualTo("SKIP");
        assertThat(ProactiveService.parseDecision(null).type()).isEqualTo("SKIP");
    }
}
