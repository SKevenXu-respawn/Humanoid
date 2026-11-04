import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../database_util.dart';
import 'bot_prompt.dart';

class BotPromptInfo {
  const BotPromptInfo({
    required this.key,
    required this.label,
    required this.content,
    required this.source,
  });

  final String key;
  final String label;
  final String content;
  final String source;
}

class BotPromptService {
  static const system = 'system_prompt';
  static const summary = 'summary_prompt';
  static const proactive = 'proactive_prompt';
  static const taskExecutor = 'task_executor_prompt';
  static const memorySummary = 'memory_summary_prompt';

  static const _labels = <String, String>{
    system: '系统提示词',
    summary: '滚动总结提示词',
    proactive: '主动行为提示词',
    taskExecutor: '任务执行器提示词',
    memorySummary: '记忆总结提示词',
  };

  static const _defaults = <String, String>{
    system: botSystemPrompt,
    summary: botSummaryPrompt,
    proactive: botProactivePrompt,
    taskExecutor: botTaskExecutorPrompt,
    memorySummary: botMemorySummaryPrompt,
  };

  Future<String> get(String key) async {
    final fallback = _defaults[key];
    if (fallback == null) {
      throw ArgumentError('未知提示词: $key');
    }
    try {
      final rows = await DatabaseUtil.instance.db.query(
        'bot_prompt',
        columns: ['content'],
        where: 'prompt_key = ?',
        whereArgs: [key],
        limit: 1,
      );
      final content = rows.isEmpty
          ? ''
          : (rows.first['content']?.toString().trim() ?? '');
      if (content.isNotEmpty) {
        return content;
      }
    } catch (_) {
      // Older databases may not have the prompt table yet.
    }
    return fallback;
  }

  Future<String> systemPrompt() => get(system);

  Future<String> summaryPrompt() => get(summary);

  Future<String> proactivePrompt() => get(proactive);

  Future<String> taskExecutorPrompt() => get(taskExecutor);

  Future<String> memorySummaryPrompt() => get(memorySummary);

  Future<String> systemPromptFor(int userId) async {
    try {
      final rows = await DatabaseUtil.instance.db.query(
        'bot_user_prompt',
        columns: ['content'],
        where: 'user_id = ?',
        whereArgs: [userId],
        limit: 1,
      );
      final content = rows.isEmpty
          ? ''
          : (rows.first['content']?.toString().trim() ?? '');
      if (content.isNotEmpty) {
        return content;
      }
    } catch (_) {
      // Falls back to the global prompt.
    }
    return systemPrompt();
  }

  Future<List<BotPromptInfo>> list() async {
    final result = <BotPromptInfo>[];
    for (final entry in _labels.entries) {
      var content = _defaults[entry.key] ?? '';
      var source = 'DEFAULT';
      try {
        final rows = await DatabaseUtil.instance.db.query(
          'bot_prompt',
          columns: ['content'],
          where: 'prompt_key = ?',
          whereArgs: [entry.key],
          limit: 1,
        );
        final stored = rows.isEmpty
            ? ''
            : (rows.first['content']?.toString().trim() ?? '');
        if (stored.isNotEmpty) {
          content = stored;
          source = 'DB';
        }
      } catch (_) {
        // Ignore missing table and return defaults.
      }
      result.add(
        BotPromptInfo(
          key: entry.key,
          label: entry.value,
          content: content,
          source: source,
        ),
      );
    }
    return result;
  }

  Future<void> save(String key, String content) async {
    if (!_defaults.containsKey(key)) {
      throw ArgumentError('未知提示词: $key');
    }
    final text = content.trim();
    if (text.isEmpty) {
      throw ArgumentError('提示词不能为空；如需恢复默认请使用重置');
    }
    await DatabaseUtil.instance.db.insert(
      'bot_prompt',
      {
        'prompt_key': key,
        'content': text,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> reset(String key) async {
    if (!_defaults.containsKey(key)) {
      throw ArgumentError('未知提示词: $key');
    }
    await DatabaseUtil.instance.db.delete(
      'bot_prompt',
      where: 'prompt_key = ?',
      whereArgs: [key],
    );
  }

  Future<String?> userPrompt(int userId) async {
    try {
      final rows = await DatabaseUtil.instance.db.query(
        'bot_user_prompt',
        columns: ['content'],
        where: 'user_id = ?',
        whereArgs: [userId],
        limit: 1,
      );
      if (rows.isEmpty) {
        return null;
      }
      final content = rows.first['content']?.toString().trim() ?? '';
      return content.isEmpty ? null : content;
    } catch (_) {
      return null;
    }
  }

  Future<void> saveUserPrompt(int userId, String content) async {
    final text = content.trim();
    if (text.isEmpty) {
      throw ArgumentError('提示词不能为空');
    }
    await DatabaseUtil.instance.db.insert(
      'bot_user_prompt',
      {
        'user_id': userId,
        'content': text,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> deleteUserPrompt(int userId) async {
    await DatabaseUtil.instance.db.delete(
      'bot_user_prompt',
      where: 'user_id = ?',
      whereArgs: [userId],
    );
  }
}
