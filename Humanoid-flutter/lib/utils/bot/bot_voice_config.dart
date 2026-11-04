import '../database_util.dart';

class BotVoiceConfig {
  const BotVoiceConfig({
    required this.enabled,
    required this.speaker,
    required this.resourceId,
  });

  final bool enabled;
  final String speaker;
  final String resourceId;

  static Future<BotVoiceConfig> load() async {
    Map<String, Object?>? row;
    try {
      final rows = await DatabaseUtil.instance.db.query(
        'bot_voice_config',
        limit: 1,
      );
      if (rows.isNotEmpty) {
        row = rows.first;
      }
    } catch (_) {
      // Older databases may not have this table yet.
    }

    final enabledSetting =
        await DatabaseUtil.instance.getSetting('voice_enabled');
    final enabled = row == null
        ? (enabledSetting ?? '1') != '0'
        : (row['enabled'] as int? ?? 1) != 0;

    final speakerSetting =
        (await DatabaseUtil.instance.getSetting('voice_speaker'))?.trim() ??
            '';
    final storedSpeaker = row?['speaker']?.toString().trim() ?? '';
    final speaker = storedSpeaker.isNotEmpty
        ? storedSpeaker
        : (speakerSetting.isEmpty
            ? 'zh_female_jiaochuannv_uranus_bigtts'
            : speakerSetting);

    final resourceSetting =
        (await DatabaseUtil.instance.getSetting('voice_resource_id'))?.trim() ??
            '';
    return BotVoiceConfig(
      enabled: enabled,
      speaker: speaker,
      resourceId: resourceSetting.isEmpty
          ? 'seed-tts-2.0'
          : resourceSetting,
    );
  }
}
