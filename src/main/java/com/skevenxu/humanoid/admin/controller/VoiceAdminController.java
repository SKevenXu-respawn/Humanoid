package com.skevenxu.humanoid.admin.controller;

import com.skevenxu.humanoid.admin.dto.AdminDtos.VoiceConfigUpdateRequest;
import com.skevenxu.humanoid.admin.dto.AdminDtos.VoiceConfigView;
import com.skevenxu.humanoid.ai.voice.VoiceConfigService;
import com.skevenxu.humanoid.entity.BotVoiceConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/voice")
public class VoiceAdminController {

    private final VoiceConfigService voiceConfigService;

    public VoiceAdminController(VoiceConfigService voiceConfigService) {
        this.voiceConfigService = voiceConfigService;
    }

    @GetMapping
    public VoiceConfigView get() {
        return toView(voiceConfigService.current());
    }

    @PutMapping
    public VoiceConfigView update(@RequestBody VoiceConfigUpdateRequest request) {
        BotVoiceConfig updated = voiceConfigService.update(
                request == null ? null : request.enabled(),
                request == null ? null : request.speaker());
        return toView(updated);
    }

    private VoiceConfigView toView(BotVoiceConfig config) {
        return new VoiceConfigView(Boolean.TRUE.equals(config.getEnabled()),
                config.getSpeaker(), voiceConfigService.defaultSpeaker());
    }
}
