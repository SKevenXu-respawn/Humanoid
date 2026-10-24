package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReminderServiceTest {

    @Test
    void sendsPresetPromptToMainAiInsteadOfDirectMessage() {
        BotReminderMapper reminderMapper = mock(BotReminderMapper.class);
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        ContextStore contextStore = mock(ContextStore.class);
        AiChatService aiChatService = mock(AiChatService.class);
        ReminderService service = new ReminderService(
                reminderMapper, conversationMapper, contextStore, aiChatService, 3);

        BotReminder reminder = new BotReminder();
        reminder.setId(7L);
        reminder.setUserId(10001L);
        reminder.setMessageType("private");
        reminder.setGroupId(0L);
        reminder.setContent("提醒我今晚八点开会，并查一下明天的天气");
        reminder.setRemindAt(LocalDateTime.now().minusMinutes(1));
        reminder.setStatus(BotReminder.STATUS_PENDING);
        reminder.setAttempts(0);
        when(reminderMapper.selectList(any())).thenReturn(List.of(reminder));
        when(reminderMapper.update(any(), any())).thenReturn(1);

        service.dispatch();

        ArgumentCaptor<BotConversation> captor = ArgumentCaptor.forClass(BotConversation.class);
        verify(conversationMapper).insert(captor.capture());
        BotConversation trigger = captor.getValue();
        assertThat(trigger.getContent())
                .contains("[定时提醒触发]")
                .contains("提醒我今晚八点开会，并查一下明天的天气");
        assertThat(trigger.getRole()).isEqualTo("user");

        verify(contextStore).appendUser(eq(10001L), eq("private"), eq(0L),
                eq(trigger.getContent()), anyList());
        verify(aiChatService).replyAsync(trigger);

        assertThat(reminder.getStatus()).isEqualTo(BotReminder.STATUS_SENT);
        assertThat(reminder.getSentAt()).isNotNull();
    }

    @Test
    void keepsPendingForRetryWhenTriggerFails() {
        BotReminderMapper reminderMapper = mock(BotReminderMapper.class);
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        ContextStore contextStore = mock(ContextStore.class);
        AiChatService aiChatService = mock(AiChatService.class);
        ReminderService service = new ReminderService(
                reminderMapper, conversationMapper, contextStore, aiChatService, 3);

        BotReminder reminder = new BotReminder();
        reminder.setId(8L);
        reminder.setUserId(10001L);
        reminder.setMessageType("private");
        reminder.setGroupId(0L);
        reminder.setContent("提醒");
        reminder.setRemindAt(LocalDateTime.now().minusMinutes(1));
        reminder.setStatus(BotReminder.STATUS_PENDING);
        reminder.setAttempts(0);
        when(reminderMapper.selectList(any())).thenReturn(List.of(reminder));
        when(reminderMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(conversationMapper).insert(any(BotConversation.class));

        service.dispatch();

        assertThat(reminder.getStatus()).isEqualTo(BotReminder.STATUS_PENDING);
        assertThat(reminder.getAttempts()).isEqualTo(1);
        verify(reminderMapper).updateById(reminder);
    }

    @Test
    void skipsReminderClaimedByAnotherInstance() {
        BotReminderMapper reminderMapper = mock(BotReminderMapper.class);
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        ContextStore contextStore = mock(ContextStore.class);
        AiChatService aiChatService = mock(AiChatService.class);
        ReminderService service = new ReminderService(
                reminderMapper, conversationMapper, contextStore, aiChatService, 3);

        BotReminder reminder = new BotReminder();
        reminder.setId(9L);
        reminder.setUserId(10001L);
        reminder.setMessageType("private");
        reminder.setGroupId(0L);
        reminder.setContent("提醒");
        reminder.setRemindAt(LocalDateTime.now().minusMinutes(1));
        reminder.setStatus(BotReminder.STATUS_PENDING);
        reminder.setAttempts(0);
        when(reminderMapper.selectList(any())).thenReturn(List.of(reminder));
        when(reminderMapper.update(any(), any())).thenReturn(0);

        service.dispatch();

        verify(conversationMapper, org.mockito.Mockito.never()).insert(any(BotConversation.class));
        verify(aiChatService, org.mockito.Mockito.never()).replyAsync(any(BotConversation.class));
        assertThat(reminder.getAttempts()).isZero();
    }
}
