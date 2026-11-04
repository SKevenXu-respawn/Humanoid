import '../terminal/terminal_util.dart';
import 'bot_llm.dart';
import 'bot_models.dart';
import 'bot_prompt_service.dart';

class BotMemorySummaryService {
  BotMemorySummaryService({required this.repository});

  final BotRepository repository;
  bool _running = false;

  Future<void> tick() async {
    if (_running) {
      return;
    }
    _running = true;
    try {
      final cutoff = DateTime.now()
          .subtract(const Duration(days: 7))
          .millisecondsSinceEpoch;
      final groups = await repository.memoryGroupsForSummary(
        minCount: 10,
        limit: 20,
        updatedBefore: cutoff,
      );
      for (final group in groups) {
        final memoryType = group['memory_type']?.toString() ?? '';
        final scopeType = group['scope_type']?.toString() ?? '';
        final scopeId = group['scope_id'] as int? ?? 0;
        final ownerUserId = group['owner_user_id'] as int? ?? 0;
        final memories = await repository.activeMemoriesForGroup(
          memoryType: memoryType,
          scopeType: scopeType,
          scopeId: scopeId,
          ownerUserId: ownerUserId,
          limit: 200,
          updatedBefore: cutoff,
        );
        if (memories.length < 10) {
          continue;
        }
        final existing = await repository.memorySummary(
          scopeType: scopeType,
          scopeId: scopeId,
          ownerUserId: ownerUserId,
        );
        final transcript = memories
            .map((memory) => '- ${memory['content']?.toString() ?? ''}')
            .join('\n');
        final summary = await BotLlm.complete(
          system: await BotPromptService().memorySummaryPrompt(),
          user:
              '已有记忆总结：\n${existing?.trim().isNotEmpty == true ? existing : '无'}\n\n'
              '需要压缩的记忆：\n$transcript',
        );
        if (summary.isEmpty) {
          continue;
        }
        await repository.saveMemorySummary(
          scopeType: scopeType,
          scopeId: scopeId,
          ownerUserId: ownerUserId,
          summary: summary,
        );
        await repository.markMemoriesSummarized(
          memories
              .map((memory) => memory['id'] as int?)
              .whereType<int>()
              .toList(),
        );
        TerminalUtil.instance.appendBotLog(
          '[Bot] 已压缩 $memoryType 记忆 ${memories.length} 条',
        );
      }
    } catch (e) {
      TerminalUtil.instance.appendBotLog('[Bot] 记忆压缩失败: $e');
    } finally {
      _running = false;
    }
  }
}
