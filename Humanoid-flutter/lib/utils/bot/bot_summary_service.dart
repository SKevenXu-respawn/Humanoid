import '../terminal/terminal_util.dart';
import 'bot_llm.dart';
import 'bot_models.dart';
import 'bot_prompt_service.dart';

class BotSummaryService {
  BotSummaryService({required this.repository, this.threshold = 100});

  final BotRepository repository;
  final int threshold;
  final Set<String> _running = {};

  Future<void> maybeSummarize(
    BotScope scope, {
    int? beforeConversationId,
  }) async {
    if (_running.contains(scope.key)) {
      return;
    }
    _running.add(scope.key);
    try {
      final row = await repository.summaryRow(scope);
      final lastId = row?['last_conversation_id'] as int? ?? 0;
      final pending = await repository.pendingConversations(
        scope,
        lastId,
        threshold,
        beforeId: beforeConversationId,
      );
      if (pending.length < threshold) {
        return;
      }

      final transcript = pending.map((message) {
        final role = message['role'] == 'bot' ? '助手' : '用户';
        final raw = message['content']?.toString() ?? '[空]';
        final content = raw.replaceAll(RegExp(r'\s+'), ' ').trim();
        return '$role: ${content.length > 1000 ? '${content.substring(0, 1000)}…' : content}';
      }).join('\n');

      final previous = row?['summary']?.toString() ?? '无';
      final summary = await BotLlm.complete(
        system: await BotPromptService().summaryPrompt(),
        user:
            '以前的总结为：\n$previous\n\n以下是需要合并的对话：\n$transcript',
      );
      if (summary.isEmpty) {
        return;
      }
      final lastConversationId = pending.last['id'] as int? ?? lastId;
      await repository.saveSummary(scope, summary, lastConversationId);
      TerminalUtil.instance.appendBotLog(
        '[Bot] 已更新对话总结（${summary.length} 字）',
      );
    } catch (e) {
      TerminalUtil.instance.appendBotLog('[Bot] 对话总结失败: $e');
    } finally {
      _running.remove(scope.key);
    }
  }
}
