import 'dart:convert';
import 'dart:io';

import 'package:dart_agent_core/dart_agent_core.dart';
import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;

import '../database_util.dart';
import 'bot_llm.dart';
import 'bot_models.dart';
import 'bot_paths.dart';
import 'bot_prompt_sanitizer.dart';
import 'bot_prompt_service.dart';
import 'bot_skill_service.dart';
import 'bot_tools.dart';

class BotConfigException implements Exception {
  final String message;

  const BotConfigException(this.message);

  @override
  String toString() => message;
}

class BotAgentService {
  BotAgentService({
    required this.repository,
    required this.tools,
    required this.skills,
  });

  final BotRepository repository;
  final BotToolRegistry tools;
  final BotSkillService skills;

  static const _maxImages = 4;
  static const _imageWindow = 8;
  static const _maxImageBytes = 10 * 1024 * 1024;

  Future<String> generateReply({
    required BotScope scope,
    required String userText,
    required int currentContextId,
    List<BotImageInput> images = const [],
  }) async {
    await BotPaths.ensureUserDirs(scope.userId);
    final apiKey = (await DatabaseUtil.instance.getSecretValue('deepseek_key'))
            ?.trim() ??
        '';
    if (apiKey.isEmpty) {
      throw const BotConfigException('DeepSeek Key 未配置，请先在配置页填写');
    }

    final model = (await DatabaseUtil.instance.getSetting('deepseek_model'))
            ?.trim() ??
        '';
    final baseUrl =
        (await DatabaseUtil.instance.getSetting('deepseek_base_url'))?.trim() ??
            '';
    final modelName = model.isEmpty ? 'deepseek-flash' : model;
    final modelBaseUrl =
        baseUrl.isEmpty ? 'https://api.deepseek.com' : baseUrl;

    final rows = await repository.recentContext(
      scope,
      limit: 100,
      beforeId: currentContextId,
    );
    final history = await _buildHistory(scope, rows, modelName);

    final prompts = BotPromptService();
    final system = StringBuffer(
      BotPromptSanitizer.clean(await prompts.systemPromptFor(scope.userId)),
    );
    final summary = BotPromptSanitizer.clean(await repository.getSummary(scope));
    if (summary.trim().isNotEmpty) {
      system.write('\n\n以前的总结为：\n$summary');
    }
    system.write(BotPromptSanitizer.clean(_fileLayoutBrief(scope)));
    final activeTask = await _activeTaskBrief(scope);
    if (activeTask.isNotEmpty) {
      system.write('\n\n当前未完成任务：\n$activeTask');
    }
    final skillsBrief =
        BotPromptSanitizer.clean(await skills.brief(userId: scope.userId));
    if (skillsBrief.isNotEmpty) {
      system.write('\n\n可用技能（需要时先用 useSkill 加载详细步骤）：\n$skillsBrief');
    }
    system.write(
      BotPromptSanitizer.clean(await repository.memoryBlock(scope)),
    );
    system.write(
      BotPromptSanitizer.clean(await repository.memorySummaryBlock(scope)),
    );
    final recentToolCalls = BotPromptSanitizer.clean(await _recentToolCalls(scope));
    if (recentToolCalls.isNotEmpty) {
      system.write('\n\n最近工具调用：\n$recentToolCalls');
    }

    final state = AgentState(
      sessionId: scope.key,
      history: AgentMessageHistory(messages: history),
      metadata: scope.toMetadata(),
    );

    final agent = StatefulAgent(
      name: 'humanoid_bot',
      client: OpenAIClient(
        apiKey: apiKey,
        baseUrl: modelBaseUrl,
        timeout: const Duration(seconds: 180),
        connectTimeout: const Duration(seconds: 20),
        maxRetries: 2,
      ),
      modelConfig: ModelConfig(model: modelName, temperature: 0.7),
      state: state,
      tools: tools.build(),
      systemPrompts: [BotPromptSanitizer.clean(system.toString())],
      maxTurns: 20,
      withGeneralPrinciples: false,
      disableSubAgents: true,
    );

    final currentMessage = images.isEmpty
        ? UserMessage.text(userText)
        : UserMessage(<UserContentPart>[
            if (userText.trim().isNotEmpty) TextPart(userText),
            for (final image in images)
              ImagePart(image.base64, image.mimeType),
          ]);
    final responses = await agent.run(
      [currentMessage],
      useStream: false,
    );
    String? finalText;
    for (final message in responses.reversed) {
      if (message is ModelMessage) {
        final text = message.textOutput?.trim();
        if (text != null && text.isNotEmpty) {
          finalText = text;
          break;
        }
      }
    }
    if (finalText == null || finalText.isEmpty) {
      throw StateError('AI 没有返回内容');
    }
    return finalText;
  }

  Future<String> generateTaskStep({
    required BotScope scope,
    required Map<String, Object?> task,
    required Map<String, Object?> step,
    required List<Map<String, Object?>> steps,
  }) async {
    await BotPaths.ensureUserDirs(scope.userId);
    final config = await BotLlmConfig.load();
    if (config.apiKey.isEmpty) {
      throw const BotConfigException('DeepSeek Key 未配置，请先在配置页填写');
    }

    final prompts = BotPromptService();
    final system = StringBuffer(
      BotPromptSanitizer.clean(await prompts.taskExecutorPrompt()),
    );
    system.write(BotPromptSanitizer.clean(_fileLayoutBrief(scope)));
    system.write('\n\n当前时间：${_currentTimeText()}\n');
    system.write('\n\n任务 #${task['id']}：${task['title']}\n全部步骤：\n');
    for (final item in steps) {
      final completed = item['status'] == 'COMPLETED';
      system.write(
        '  ${item['step_no']}. ${completed ? '[x]' : '[ ]'} ${item['content']}',
      );
      if (item['id'] == step['id']) {
        system.write('  ← 当前步骤');
      }
      system.write('\n');
    }
    system.write(
      '\n请执行当前步骤：第 ${step['step_no']} 步「${step['content']}」。\n',
    );
    system.write('调用 completeStep / pauseTask 时请带上 taskId=${task['id']}。');

    final state = AgentState(
      sessionId: 'task:${task['id']}',
      metadata: scope.toMetadata(),
    );
    final agent = StatefulAgent(
      name: 'humanoid_task_executor',
      client: OpenAIClient(
        apiKey: config.apiKey,
        baseUrl: config.baseUrl,
        timeout: const Duration(seconds: 180),
        connectTimeout: const Duration(seconds: 20),
        maxRetries: 2,
      ),
      modelConfig: ModelConfig(model: config.model, temperature: 0.5),
      state: state,
      tools: tools.buildForExecutor(),
      systemPrompts: [BotPromptSanitizer.clean(system.toString())],
      maxTurns: 20,
      withGeneralPrinciples: false,
      disableSubAgents: true,
    );

    final responses = await agent.run(
      [UserMessage.text('请执行当前步骤。')],
      useStream: false,
    );
    String? finalText;
    for (final message in responses.reversed) {
      if (message is ModelMessage) {
        final text = message.textOutput?.trim();
        if (text != null && text.isNotEmpty) {
          finalText = text;
          break;
        }
      }
    }
    return finalText ?? '';
  }

  Future<String> generateProactiveReply({
    required BotScope scope,
    required String context,
  }) async {
    final config = await BotLlmConfig.load();
    if (config.apiKey.isEmpty) {
      throw const BotConfigException('DeepSeek Key 未配置，请先在配置页填写');
    }
    final system = BotPromptSanitizer.clean(
      await BotPromptService().proactivePrompt(),
    );
    final state = AgentState(
      sessionId: 'proactive:${scope.key}',
      metadata: scope.toMetadata(),
    );
    final agent = StatefulAgent(
      name: 'humanoid_proactive',
      client: OpenAIClient(
        apiKey: config.apiKey,
        baseUrl: config.baseUrl,
        timeout: const Duration(seconds: 180),
        connectTimeout: const Duration(seconds: 20),
        maxRetries: 2,
      ),
      modelConfig: ModelConfig(model: config.model, temperature: 0.7),
      state: state,
      tools: tools.buildForProactive(),
      systemPrompts: [system],
      maxTurns: 10,
      withGeneralPrinciples: false,
      disableSubAgents: true,
    );
    final responses = await agent.run(
      [UserMessage.text(context)],
      useStream: false,
    );
    for (final message in responses.reversed) {
      if (message is ModelMessage) {
        final text = message.textOutput?.trim();
        if (text != null && text.isNotEmpty) {
          return text;
        }
      }
    }
    return '';
  }

  Future<List<LLMMessage>> _buildHistory(
    BotScope scope,
    List<Map<String, Object?>> rows,
    String modelName,
  ) async {
    final history = <LLMMessage>[];
    var remainingImages = _maxImages;
    final imageStart = rows.length > _imageWindow ? rows.length - _imageWindow : 0;
    for (var index = 0; index < rows.length; index++) {
      final row = rows[index];
      final role = row['role']?.toString();
      final rawContent = (row['content']?.toString() ?? '').trim();
      if (rawContent.isEmpty) {
        continue;
      }
      final assistant = role == 'assistant';
      final content = assistant
          ? _cleanAssistantContent(BotPromptSanitizer.clean(rawContent))
          : BotPromptSanitizer.clean(rawContent);
      if (content.isEmpty) {
        continue;
      }

      final images = <BotImageInput>[];
      if (index >= imageStart && remainingImages > 0) {
        for (final ref in _parseImageRefs(row['image_refs']?.toString())) {
          if (remainingImages == 0) {
            break;
          }
          final image = await _loadImageRef(scope, ref);
          if (image != null) {
            images.add(image);
            remainingImages--;
          }
        }
      }

      if (images.isNotEmpty) {
        final prefix = assistant ? '[助手之前发送的图片] ' : '';
        history.add(
          UserMessage(<UserContentPart>[
            TextPart('$prefix$content'),
            for (final image in images)
              ImagePart(image.base64, image.mimeType),
          ]),
        );
      } else {
        history.add(
          assistant
              ? ModelMessage(textOutput: content, model: modelName)
              : UserMessage.text(content),
        );
      }
    }
    return history;
  }

  Future<BotImageInput?> _loadImageRef(BotScope scope, String ref) async {
    try {
      final value = ref.trim();
      if (value.isEmpty) {
        return null;
      }
      if (value.startsWith('image:')) {
        final id = int.tryParse(value.substring('image:'.length));
        if (id == null) {
          return null;
        }
        final row = await repository.findImage(scope, imageId: id);
        final dataUrl = row?['data_url']?.toString();
        return dataUrl == null ? null : _imageFromDataUrl(dataUrl);
      }
      if (value.startsWith('data:')) {
        return _imageFromDataUrl(value);
      }
      if (value.startsWith('base64://')) {
        return _imageFromDataUrl(
          'data:image/jpeg;base64,${value.substring('base64://'.length)}',
        );
      }
      if (value.startsWith('http://') || value.startsWith('https://')) {
        final response = await Dio(
          BaseOptions(
            connectTimeout: const Duration(seconds: 10),
            receiveTimeout: const Duration(seconds: 30),
          ),
        ).get<List<int>>(
          value,
          options: Options(
            responseType: ResponseType.bytes,
            followRedirects: true,
          ),
        );
        final bytes = response.data ?? const [];
        if (bytes.isEmpty || bytes.length > _maxImageBytes) {
          return null;
        }
        final contentType =
            response.headers.value(Headers.contentTypeHeader);
        return BotImageInput(
          base64: base64Encode(bytes),
          mimeType: _detectImageMime(bytes, contentType),
        );
      }

      final root = p.normalize(p.absolute(BotPaths.userRoot(scope.userId).path));
      final path = p.normalize(p.absolute(p.join(root, value)));
      if (path != root && !p.isWithin(root, path)) {
        return null;
      }
      final file = File(path);
      if (!await file.exists()) {
        return null;
      }
      final bytes = await file.readAsBytes();
      if (bytes.isEmpty || bytes.length > _maxImageBytes) {
        return null;
      }
      return BotImageInput(
        base64: base64Encode(bytes),
        mimeType: _detectImageMime(bytes, null),
      );
    } catch (_) {
      return null;
    }
  }

  static BotImageInput? _imageFromDataUrl(String dataUrl) {
    final comma = dataUrl.indexOf(',');
    if (comma <= 0) {
      return null;
    }
    final header = dataUrl.substring(0, comma);
    final encoded = dataUrl.substring(comma + 1).trim();
    if (encoded.isEmpty) {
      return null;
    }
    final match = RegExp(r'data:([^;]+);base64').firstMatch(header);
    return BotImageInput(
      base64: encoded,
      mimeType: match?.group(1) ?? 'image/jpeg',
    );
  }

  static List<String> _parseImageRefs(String? json) {
    if (json == null || json.trim().isEmpty) {
      return const [];
    }
    try {
      final decoded = jsonDecode(json);
      if (decoded is List) {
        return decoded
            .map((item) => item?.toString().trim() ?? '')
            .where((item) => item.isNotEmpty)
            .toList(growable: false);
      }
    } catch (_) {
      // Ignore malformed image_refs.
    }
    return const [];
  }

  static String _detectImageMime(List<int> bytes, String? contentType) {
    final type = contentType?.split(';').first.trim().toLowerCase();
    if (type != null && type.startsWith('image/')) {
      return type;
    }
    if (bytes.length >= 3 &&
        bytes[0] == 0xFF &&
        bytes[1] == 0xD8 &&
        bytes[2] == 0xFF) {
      return 'image/jpeg';
    }
    if (bytes.length >= 8 &&
        bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return 'image/png';
    }
    if (bytes.length >= 6 &&
        bytes[0] == 0x47 &&
        bytes[1] == 0x49 &&
        bytes[2] == 0x46) {
      return 'image/gif';
    }
    if (bytes.length >= 12 &&
        bytes[8] == 0x57 &&
        bytes[9] == 0x45 &&
        bytes[10] == 0x42 &&
        bytes[11] == 0x50) {
      return 'image/webp';
    }
    return 'image/jpeg';
  }

  static String _cleanAssistantContent(String text) {
    if (text.trim().isEmpty) {
      return '';
    }
    final cleaned = text
        .replaceAll(
          RegExp(r'<ds_safety>.*?</ds_safety>', dotAll: true),
          '',
        )
        .trim();
    final buffer = StringBuffer();
    for (final line in cleaned.split(RegExp(r'\r?\n'))) {
      if (RegExp(r'^[A-Za-z][A-Za-z0-9_]*\(\{.*\}\)\s*$')
          .hasMatch(line.trim())) {
        continue;
      }
      buffer.writeln(line);
    }
    return buffer.toString().trim();
  }

  Future<String> _activeTaskBrief(BotScope scope) async {
    final task = await repository.activeTask(scope);
    if (task == null) {
      return '';
    }
    final taskId = task['id'] as int?;
    if (taskId == null) {
      return '';
    }
    final steps = await repository.taskSteps(taskId);
    final done =
        steps.where((step) => step['status'] == 'COMPLETED').length;
    final buffer = StringBuffer(
      '任务 #$taskId「${task['title']}」状态=${task['status']}'
      '（$done/${steps.length}）',
    );
    for (final step in steps) {
      final completed = step['status'] == 'COMPLETED';
      buffer.writeln(
        '\n  ${step['step_no']}. ${completed ? '[x]' : '[ ]'} ${step['content']}',
      );
    }
    return buffer.toString().trimRight();
  }

  static String _fileLayoutBrief(BotScope scope) {
    final root = BotPaths.userRoot(scope.userId).path;
    final memory = BotPaths.userMemoryDir(scope.userId).path;
    final skillDir = BotPaths.userSkillsDir(scope.userId).path;
    final toolDir = BotPaths.userToolsDir(scope.userId).path;
    return '\n\n文件目录根路径：$root'
        '（memory=$memory，skills=$skillDir，tools=$toolDir）。'
        '相对路径默认相对当前 QQ 目录；禁止访问其他 QQ 的目录。';
  }

  static String _currentTimeText() {
    final now = DateTime.now();
    const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
    final weekday = weekdays[now.weekday - 1];
    String two(int value) => value.toString().padLeft(2, '0');
    return '${now.year}-${two(now.month)}-${two(now.day)} '
        '${two(now.hour)}:${two(now.minute)}:${two(now.second)} 星期$weekday';
  }

  Future<String> _recentToolCalls(BotScope scope) async {
    final calls = await repository.recentToolCalls(scope, limit: 20);
    if (calls.isEmpty) {
      return '';
    }
    final buffer = StringBuffer();
    for (final call in calls) {
      final name = call['tool_name']?.toString() ?? 'tool';
      final arguments = _abbrev(call['arguments']?.toString() ?? '', 80);
      final result = _abbrev(call['result']?.toString() ?? '', 150);
      buffer.writeln('- $name($arguments) → $result');
    }
    return buffer.toString().trimRight();
  }

  static String _abbrev(String value, int max) {
    final singleLine = value.replaceAll(RegExp(r'\s+'), ' ').trim();
    return singleLine.length > max
        ? '${singleLine.substring(0, max)}…'
        : singleLine;
  }
}
