import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('任务、提醒、视频队列和主动状态仓储', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    final repo = BotRepository();
    const scope = BotScope(userId: 10001, messageType: 'private');

    final taskId = await repo.addTask(scope, '测试任务');
    expect(taskId, greaterThan(0));
    await repo.addTaskStep(taskId, '第一步');
    await repo.addTaskStep(taskId, '第二步');
    await repo.updateTaskStatus(taskId, 'IN_PROGRESS');

    var steps = await repo.taskSteps(taskId);
    expect(steps.length, 2);
    final pending = await repo.nextPendingStep(taskId);
    expect(pending, isNotNull);
    final attempts = await repo.incrementStepAttempts(pending!['id'] as int);
    expect(attempts, 1);
    await repo.updateStepStatus(pending['id'] as int, 'COMPLETED');
    steps = await repo.taskSteps(taskId);
    expect(steps.first['status'], 'COMPLETED');
    final inProgress = await repo.nextInProgressTask();
    expect(inProgress?['id'], taskId);

    final now = DateTime.now().millisecondsSinceEpoch;
    final reminderId = await repo.addReminder(scope, '提醒内容', now - 1000);
    final due = await repo.dueReminders(now);
    expect(due.any((row) => row['id'] == reminderId), isTrue);
    await repo.markReminderSent(reminderId);
    expect(await repo.dueReminders(now), isEmpty);

    final videoId = await repo.enqueueVideo(
      scope,
      'https://example.com/video',
      title: '视频',
      comment: '好看',
    );
    expect(await repo.pendingVideoCount(), 1);
    final video = await repo.nextPendingVideo();
    expect(video?['id'], videoId);
    await repo.markVideoSent(videoId);
    expect(await repo.pendingVideoCount(), 0);

    await repo.upsertBotUser(20001, nickname: '测试用户');
    expect(await repo.isUserAllowed(20001), isTrue);
    expect(await repo.isUserAllowed(20002), isFalse);
    await repo.upsertBotGroup(30001, name: '测试群');
    expect(await repo.isGroupAllowed(30001), isTrue);
    expect(await repo.isGroupAllowed(30002), isFalse);

    await repo.touchProactiveState(
      20001,
      lastCheckedAt: now,
      lastProactiveAt: now,
    );
    final state = await repo.proactiveState(20001);
    expect(state?['last_checked_at'], now);
    expect(state?['last_proactive_at'], now);

    await repo.saveSummary(scope, '测试总结', 10);
    expect(await repo.getSummary(scope), '测试总结');
    await repo.saveMemorySummary(
      scopeType: 'USER',
      scopeId: 10001,
      ownerUserId: 10001,
      summary: '记忆总结',
    );
    expect(
      await repo.memorySummary(
        scopeType: 'USER',
        scopeId: 10001,
        ownerUserId: 10001,
      ),
      '记忆总结',
    );
  });
}
