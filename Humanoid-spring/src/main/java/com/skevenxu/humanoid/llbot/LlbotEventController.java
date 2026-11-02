package com.skevenxu.humanoid.llbot;

import com.google.gson.JsonElement;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/llbot")
public class LlbotEventController {

    private final BotEventService eventService;

    public LlbotEventController(BotEventService eventService) {
        this.eventService = eventService;
    }

    /** 接收 llbot 推送的事件（在 llbot WebUI 里把 OneBot 11 的 WebHook/上报地址配到这里）。 */
    @PostMapping("/events")
    public ResponseEntity<Void> receive(@RequestBody JsonElement payload) {
        eventService.accept(payload);
        return ResponseEntity.ok().build();
    }
}
