package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolProgressNotifierTest {

    private final CapturingClient client = new CapturingClient();
    private final ToolProgressNotifier notifier =
            new ToolProgressNotifier(client, true, 60, 8, 0);

    @AfterEach
    void cleanup() {
        notifier.end();
        ToolContext.clear();
    }

    @Test
    void sendsStartMessageThenThrottlesRepeatedSteps() {
        ToolContext.set(new ToolContext.ChatScope(10001L, "private", null, true));
        notifier.begin();

        notifier.toolStarted("WebFetch").close();
        notifier.toolStarted("browser_navigate").close();

        assertEquals(1, client.messages.size());
        assertTrue(client.messages.get(0).contains("抓取网页"));
    }

    @Test
    void sendsStepMessagesWhenIntervalElapsed() {
        ToolProgressNotifier immediate = new ToolProgressNotifier(client, true, 0, 8, 0);
        ToolContext.set(new ToolContext.ChatScope(10001L, "private", null, true));
        immediate.begin();

        immediate.toolStarted("WebFetch").close();
        immediate.toolStarted("browser_click").close();

        immediate.end();
        assertEquals(2, client.messages.size());
        assertTrue(client.messages.get(1).contains("第 2 步"));
        assertTrue(client.messages.get(1).contains("点击页面"));
    }

    @Test
    void ignoresScopesWithoutProgressEnabled() {
        ToolContext.set(new ToolContext.ChatScope(10001L, "private", null));
        notifier.begin();

        notifier.toolStarted("WebFetch").close();

        assertTrue(client.messages.isEmpty());
    }

    @Test
    void ignoresQuietTools() {
        ToolContext.set(new ToolContext.ChatScope(10001L, "private", null, true));
        notifier.begin();

        notifier.toolStarted("sendImage").close();
        notifier.toolStarted("currentTime").close();

        assertTrue(client.messages.isEmpty());
    }

    @Test
    void sendsToGroupWhenScopeIsGroup() {
        ToolContext.set(new ToolContext.ChatScope(10001L, "group", 20002L, true));
        notifier.begin();

        notifier.toolStarted("WebFetch").close();

        assertEquals(1, client.messages.size());
        assertTrue(client.messages.get(0).startsWith("group:"));
    }

    private static final class CapturingClient extends LlbotApiClient {

        private final List<String> messages = new ArrayList<>();

        private CapturingClient() {
            super("http://127.0.0.1:1", "");
        }

        @Override
        public Optional<Long> sendPrivate(Long userId, String message) {
            messages.add(message);
            return Optional.of(1L);
        }

        @Override
        public Optional<Long> sendGroup(Long groupId, String message) {
            messages.add("group:" + message);
            return Optional.of(1L);
        }
    }
}
