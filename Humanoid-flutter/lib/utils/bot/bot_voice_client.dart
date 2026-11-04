import 'dart:convert';
import 'dart:math';

import 'package:dio/dio.dart';

import '../database_util.dart';
import 'bot_voice_config.dart';

class BotVoiceClient {
  BotVoiceClient({
    required this.apiKey,
    this.resourceId = 'seed-tts-2.0',
    this.speaker = 'zh_female_jiaochuannv_uranus_bigtts',
    this.format = 'mp3',
    this.sampleRate = 24000,
    this.maxChars = 1000,
    this.maxAudioBytes = 10485760,
    this.timeout = const Duration(seconds: 90),
  });

  static const _submitUrl =
      'https://openspeech.bytedance.com/api/v3/tts/submit';
  static const _queryUrl =
      'https://openspeech.bytedance.com/api/v3/tts/query';

  final String apiKey;
  final String resourceId;
  final String speaker;
  final String format;
  final int sampleRate;
  final int maxChars;
  final int maxAudioBytes;
  final Duration timeout;

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 30),
    ),
  );

  static Future<BotVoiceClient> load() async {
    final apiKey =
        (await DatabaseUtil.instance.getSecretValue('speech_key'))?.trim() ??
            '';
    if (apiKey.isEmpty) {
      throw StateError('语音服务 Key 未配置，请先在配置页填写');
    }
    final config = await BotVoiceConfig.load();
    return BotVoiceClient(
      apiKey: apiKey,
      speaker: config.speaker,
      resourceId: config.resourceId,
    );
  }

  Future<List<int>> synthesize(
    String text, {
    List<String> instructions = const [],
  }) async {
    final content = text.trim();
    if (content.isEmpty) {
      throw ArgumentError('要合成的文本不能为空');
    }
    if (content.length > maxChars) {
      throw ArgumentError('文本过长，最多 $maxChars 字');
    }
    final taskId = await _submit(content, _normalizeInstructions(instructions));
    final audioUrl = await _poll(taskId);
    return _download(audioUrl);
  }

  List<String> _normalizeInstructions(List<String> values) {
    return values
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .map((value) => value.length > 500 ? value.substring(0, 500) : value)
        .take(5)
        .toList(growable: false);
  }

  Future<String> _submit(String text, List<String> instructions) async {
    final requestParams = <String, dynamic>{
      'text': text,
      'speaker': speaker,
      'audio_params': {
        'format': format,
        'sample_rate': sampleRate,
        'enable_timestamp': false,
      },
      'additions': '{}',
    };
    if (instructions.isNotEmpty) {
      requestParams['context_texts'] = jsonEncode(instructions);
    }
    final response = await _post(_submitUrl, {
      'user': {'uid': _randomId()},
      'unique_id': _randomId(),
      'req_params': requestParams,
    });
    final data = response['data'];
    final taskId = data is Map ? data['task_id']?.toString() : null;
    if (taskId == null || taskId.isEmpty) {
      throw StateError('语音任务提交成功但没有返回 task_id');
    }
    return taskId;
  }

  Future<String> _poll(String taskId) async {
    final deadline = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(deadline)) {
      final response = await _post(_queryUrl, {'task_id': taskId});
      final data = response['data'];
      final status =
          data is Map ? (data['task_status'] as num?)?.toInt() : null;
      if (status == 2) {
        final url = data is Map ? data['audio_url']?.toString() : null;
        if (url == null || url.isEmpty) {
          throw StateError('语音合成成功但没有返回 audio_url');
        }
        return url;
      }
      if (status == 3) {
        throw StateError('语音合成失败 task_id=$taskId');
      }
      await Future<void>.delayed(const Duration(seconds: 1));
    }
    throw StateError('语音合成超时 task_id=$taskId');
  }

  Future<List<int>> _download(String url) async {
    final response = await _dio.get<List<int>>(
      url,
      options: Options(responseType: ResponseType.bytes),
    );
    final bytes = response.data ?? const <int>[];
    if (bytes.isEmpty) {
      throw StateError('音频下载失败');
    }
    if (bytes.length > maxAudioBytes) {
      throw StateError('音频过大: ${bytes.length} 字节');
    }
    return bytes;
  }

  Future<Map<String, dynamic>> _post(
    String url,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post<dynamic>(
      url,
      data: body,
      options: Options(
        headers: {
          'Content-Type': 'application/json',
          'X-Api-Key': apiKey,
          'X-Api-Resource-Id': resourceId,
          'X-Api-Request-Id': _randomId(),
        },
        validateStatus: (_) => true,
      ),
    );
    final raw = response.data;
    final decoded = raw is String ? jsonDecode(raw) : raw;
    if (response.statusCode != 200 || decoded is! Map) {
      throw StateError('语音服务 HTTP ${response.statusCode}');
    }
    final data = Map<String, dynamic>.from(decoded);
    final code = (data['code'] as num?)?.toInt() ?? -1;
    if (code != 20000000) {
      throw StateError(
        '语音服务返回 code=$code：${data['message'] ?? ''}',
      );
    }
    return data;
  }

  static String _randomId() {
    final random = Random.secure();
    final suffix = List.generate(
      16,
      (_) => random.nextInt(16).toRadixString(16),
    ).join();
    return '${DateTime.now().microsecondsSinceEpoch}-$suffix';
  }
}
