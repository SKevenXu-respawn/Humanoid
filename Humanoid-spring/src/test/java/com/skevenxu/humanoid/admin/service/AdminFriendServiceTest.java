package com.skevenxu.humanoid.admin.service;

import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotFriendRequestMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminFriendServiceTest {

    @Test
    void usesCustomNoticeWhenProvided() {
        LlbotApiClient llbot = mock(LlbotApiClient.class);
        when(llbot.sendPrivate(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(llbot.deleteFriend(anyLong())).thenReturn(true);
        AdminFriendService service = new AdminFriendService(llbot, mock(BotFriendRequestMapper.class));

        service.delete(10001L, "custom notice");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llbot).sendPrivate(eq(10001L), captor.capture());
        assertThat(captor.getValue()).isEqualTo("custom notice");
        verify(llbot).deleteFriend(10001L);
    }

    @Test
    void usesDefaultNoticeWhenBlank() {
        LlbotApiClient llbot = mock(LlbotApiClient.class);
        when(llbot.sendPrivate(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(llbot.deleteFriend(anyLong())).thenReturn(true);
        AdminFriendService service = new AdminFriendService(llbot, mock(BotFriendRequestMapper.class));

        service.delete(10002L, "   ");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llbot).sendPrivate(eq(10002L), captor.capture());
        assertThat(captor.getValue())
                .contains("人机已重构功能")
                .contains("1990614908");
        verify(llbot).deleteFriend(10002L);
    }

    @Test
    void abortsDeleteWhenNoticeFails() {
        LlbotApiClient llbot = mock(LlbotApiClient.class);
        when(llbot.sendPrivate(anyLong(), anyString())).thenReturn(Optional.empty());
        AdminFriendService service = new AdminFriendService(llbot, mock(BotFriendRequestMapper.class));

        assertThatThrownBy(() -> service.delete(10003L, "notice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已取消删除");
        verify(llbot, never()).deleteFriend(anyLong());
    }
}
