import 'package:dart_agent_core/dart_agent_core.dart';

import '../database_util.dart';

class BotLlmConfig {
  final String apiKey;
  final String baseUrl;
  final String model;

  const BotLlmConfig({
    required this.apiKey,
    required this.baseUrl,
    required this.model,
  });

  static Future<BotLlmConfig> load() async {
    final apiKey =
        (await DatabaseUtil.instance.getSecretValue('deepseek_key'))?.trim() ??
            '';
    final baseUrl =
        (await DatabaseUtil.instance.getSetting('deepseek_base_url'))?.trim() ??
            '';
    final model =
        (await DatabaseUtil.instance.getSetting('deepseek_model'))?.trim() ?? '';
    return BotLlmConfig(
      apiKey: apiKey,
      baseUrl: baseUrl.isEmpty ? 'https://api.deepseek.com' : baseUrl,
      model: model.isEmpty ? 'deepseek-flash' : model,
    );
  }
}

class BotLlm {
  const BotLlm._();

  static Future<String> complete({
    required String system,
    required String user,
    double temperature = 0.3,
  }) async {
    final config = await BotLlmConfig.load();
    if (config.apiKey.isEmpty) {
      throw StateError('DeepSeek Key 未配置，请先在配置页填写');
    }
    final client = OpenAIClient(
      apiKey: config.apiKey,
      baseUrl: config.baseUrl,
      timeout: const Duration(seconds: 180),
      connectTimeout: const Duration(seconds: 20),
      maxRetries: 2,
    );
    final response = await client.generate(
      [SystemMessage(system), UserMessage.text(user)],
      modelConfig: ModelConfig(model: config.model, temperature: temperature),
    );
    return response.textOutput?.trim() ?? '';
  }

  static Future<String> completeWithImage({
    required String system,
    required String user,
    required String imageBase64,
    required String mimeType,
    double temperature = 0.3,
  }) async {
    final config = await BotLlmConfig.load();
    if (config.apiKey.isEmpty) {
      throw StateError('DeepSeek Key 未配置，请先在配置页填写');
    }
    final client = OpenAIClient(
      apiKey: config.apiKey,
      baseUrl: config.baseUrl,
      timeout: const Duration(seconds: 180),
      connectTimeout: const Duration(seconds: 20),
      maxRetries: 2,
    );
    final response = await client.generate(
      [
        SystemMessage(system),
        UserMessage(<UserContentPart>[
          TextPart(user),
          ImagePart(imageBase64, mimeType),
        ]),
      ],
      modelConfig: ModelConfig(model: config.model, temperature: temperature),
    );
    return response.textOutput?.trim() ?? '';
  }
}
