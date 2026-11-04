import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';
import 'package:humanoid_flutter/utils/bot/bot_prompt_sanitizer.dart';
import 'package:humanoid_flutter/utils/bot/bot_prompt_service.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('任务完成会同步完成所有步骤', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    final repo = BotRepository();
    const scope = BotScope(userId: 11001, messageType: 'private');
    final taskId = await repo.addTask(scope, '测试任务');
    await repo.addTaskStep(taskId, '第一步');
    await repo.addTaskStep(taskId, '第二步');

    await repo.completeTask(taskId);
    final task = await repo.getTask(taskId);
    final steps = await repo.taskSteps(taskId);
    expect(task?['status'], 'COMPLETED');
    expect(steps.every((step) => step['status'] == 'COMPLETED'), isTrue);
  });

  test('图片、好友申请和视频队列的新字段', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    final repo = BotRepository();
    const scope = BotScope(userId: 12001, messageType: 'private');

    final imageId = await repo.storeImage(
      scope: scope,
      source: 'test.png',
      dataUrl: 'data:image/png;base64,AAAA',
    );
    expect(imageId, greaterThan(0));
    final image = await repo.findImage(scope);
    expect(image?['id'], imageId);
    expect(image?['data_url'], contains('image/png'));

    expect(
      await repo.recordFriendRequest(userId: 12001, flag: 'flag-1'),
      isTrue,
    );
    expect(
      await repo.recordFriendRequest(userId: 12001, flag: 'flag-1'),
      isFalse,
    );

    final videoId = await repo.enqueueVideo(
      scope,
      'https://example.com/v.mp4',
      title: '视频',
    );
    expect(videoId, greaterThan(0));
    final duplicate = await repo.enqueueVideo(
      scope,
      'https://example.com/v.mp4',
    );
    expect(duplicate, lessThan(0));
    const otherScope = BotScope(userId: 12002, messageType: 'private');
    expect(await repo.nextPendingVideoForScope(otherScope), isNull);
    final claimed = await repo.claimVideo(videoId, 0);
    expect(claimed, isTrue);
    expect(await repo.claimVideo(videoId, 0), isFalse);
  });

  test('提示词数据库覆盖、按 QQ 提示词与文本清理', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    final service = BotPromptService();

    final defaultPrompt = await service.systemPrompt();
    expect(defaultPrompt, isNotEmpty);

    await service.save(BotPromptService.system, '全局自定义提示词');
    expect(await service.systemPrompt(), '全局自定义提示词');

    await service.saveUserPrompt(13001, 'QQ 专属提示词');
    expect(await service.systemPromptFor(13001), 'QQ 专属提示词');
    expect(await service.systemPromptFor(13002), '全局自定义提示词');
    await service.deleteUserPrompt(13001);
    expect(await service.systemPromptFor(13001), '全局自定义提示词');

    await service.reset(BotPromptService.system);
    expect(await service.systemPrompt(), defaultPrompt);

    expect(BotPromptSanitizer.clean(r'a\b'), 'a/b');
    expect(BotPromptSanitizer.truncate('abcdef', 3), 'abc…');
  });

  test('事件解析语音与引用消息段', () {
    final event = BotEvent.parse({
      'post_type': 'message',
      'message_type': 'private',
      'user_id': 14001,
      'message_id': 14002,
      'message': [
        {
          'type': 'record',
          'data': {'file': 'voice.amr'},
        },
        {
          'type': 'reply',
          'data': {'id': 14000},
        },
      ],
    });
    expect(event?.recordFile, 'voice.amr');
    expect(event?.replyMessageId, 14000);
  });
}
