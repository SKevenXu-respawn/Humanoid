package com.skevenxu.humanoid.ai.video;

import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotVideoQueueMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoQueueServiceTest {

    @Test
    void strictModeDrainsFirstUserBeforeOthers() {
        BotVideoQueueMapper mapper = mock(BotVideoQueueMapper.class);
        LlbotApiClient llbot = mock(LlbotApiClient.class);
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        ContextStore contextStore = mock(ContextStore.class);
        VideoQueueService service = new VideoQueueService(
                mapper, llbot, conversationMapper, contextStore, true, 5, "strict");

        BotVideoQueue first = item(1L, 10001L);
        BotVideoQueue second = item(2L, 10001L);
        BotVideoQueue otherUser = item(3L, 20002L);

        when(mapper.selectOne(any())).thenReturn(first);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        when(mapper.update(any(), any())).thenReturn(1);
        when(llbot.sendPrivate(eq(10001L), anyString())).thenReturn(Optional.of(11L));

        service.dispatch();

        verify(llbot, times(2)).sendPrivate(eq(10001L), anyString());
        verify(llbot, times(0)).sendPrivate(eq(20002L), anyString());
        verify(mapper).updateById(first);
        verify(mapper).updateById(second);
        verify(mapper, times(0)).updateById(otherUser);
    }

    @Test
    void fairModeSendsOneCandidatePerUser() {
        BotVideoQueueMapper mapper = mock(BotVideoQueueMapper.class);
        LlbotApiClient llbot = mock(LlbotApiClient.class);
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        ContextStore contextStore = mock(ContextStore.class);
        VideoQueueService service = new VideoQueueService(
                mapper, llbot, conversationMapper, contextStore, true, 5, "fair");

        BotVideoQueue first = item(1L, 10001L);
        BotVideoQueue otherUser = item(3L, 20002L);
        when(mapper.selectNextPerUser(5)).thenReturn(List.of(first, otherUser));
        when(mapper.update(any(), any())).thenReturn(1);
        when(llbot.sendPrivate(anyLong(), anyString())).thenReturn(Optional.of(1L));

        service.dispatch();

        verify(llbot).sendPrivate(eq(10001L), anyString());
        verify(llbot).sendPrivate(eq(20002L), anyString());
    }

    private static BotVideoQueue item(Long id, Long userId) {
        BotVideoQueue item = new BotVideoQueue();
        item.setId(id);
        item.setUserId(userId);
        item.setMessageType("private");
        item.setGroupId(0L);
        item.setUrl("https://example.com/video/" + id);
        item.setTitle("video " + id);
        item.setStatus(BotVideoQueue.STATUS_PENDING);
        item.setAttempts(0);
        item.setCreatedAt(LocalDateTime.now());
        return item;
    }
}
