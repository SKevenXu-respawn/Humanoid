import 'dart:convert';
import 'dart:math';

import 'package:dio/dio.dart';

import '../database_util.dart';

class BotAsrClient {
  BotAsrClient({
    required this.apiKey,
    this.resourceId = 'volc.bigasr.auc_turbo',
    this.model = 'bigmodel',
    this.maxAudioBytes = 10485760,
    this.timeout = const Duration(seconds: 30),
  });

  static const _endpoint =
      'https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash';
  static const _successCode = 20000000;

  final String apiKey;
  final String resourceId;
  final String model;
  final int maxAudioBytes;
  final Duration timeout;

  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 30),
    ),
  );

  static Future<BotAsrClient?> load() async {
    final enabled =
        (await DatabaseUtil.instance.getSetting('asr_enabled') ?? '1') != '0';
    if (!enabled) {
      return null;
    }
    final key =
        (await DatabaseUtil.instance.getSecretValue('speech_key'))?.trim() ??
            '';
    if (key.isEmpty) {
      return null;
    }
    final resource =
        (await DatabaseUtil.instance.getSetting('asr_resource_id'))?.trim() ??
            '';
    final model =
        (await DatabaseUtil.instance.getSetting('asr_model'))?.trim() ?? '';
    return BotAsrClient(
      apiKey: key,
      resourceId:
          resource.isEmpty ? 'volc.bigasr.auc_turbo' : resource,
      model: model.isEmpty ? 'bigmodel' : model,
    );
  }

  Future<String?> transcribe(List<int> audio, {String format = 'mp3'}) async {
    if (audio.isEmpty || audio.length > maxAudioBytes) {
      return null;
    }
    try {
      final response = await _dio.post<dynamic>(
        _endpoint,
        data: {
          'audio': {
            'data': base64Encode(audio),
            'format': format,
          },
          'request': {
            'model_name': model,
            'enable_itn': true,
            'enable_punc': true,
            'enable_ddc': true,
          },
        },
        options: Options(
          headers: {
            'Content-Type': 'application/json',
            'X-Api-Key': apiKey,
            'X-Api-Resource-Id': resourceId,
            'X-Api-Request-Id': _randomId(),
            'X-Api-Sequence': '-1',
          },
          responseType: ResponseType.bytes,
          validateStatus: (_) => true,
        ),
      );
      final raw = response.data;
      final decoded = raw is List<int>
          ? jsonDecode(const Utf8Decoder(allowMalformed: true).convert(raw))
          : raw is String
              ? jsonDecode(raw)
              : raw;
      if (decoded is! Map) {
        return null;
      }
      final header = decoded['header'];
      final code = header is Map ? (header['code'] as num?)?.toInt() : null;
      if (response.statusCode != 200 ||
          (code != null && code != -1 && code != _successCode)) {
        return null;
      }
      final result = decoded['result'];
      final text =
          result is Map ? result['text']?.toString().trim() ?? '' : '';
      return text.isEmpty ? null : text;
    } catch (_) {
      return null;
    }
  }

  static String _randomId() {
    final random = Random.secure();
    final suffix = List.generate(
      24,
      (_) => random.nextInt(16).toRadixString(16),
    ).join();
    return '${DateTime.now().microsecondsSinceEpoch}-$suffix';
  }
}
