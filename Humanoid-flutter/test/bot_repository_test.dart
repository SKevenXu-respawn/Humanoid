import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('Bot 仓储的核心读写', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);

    final repo = BotRepository();
    const privateScope = BotScope(userId: 123, messageType: 'private');
    const groupScope = BotScope(
      userId: 123,
      messageType: 'group',
      groupId: 456,
    );

    expect(await repo.isUserAllowed(123), isFalse);
    expect(await repo.isGroupAllowed(456), isFalse);

    await DatabaseUtil.instance.db.insert('bot_user', {
      'user_id': 123,
      'nickname': 'tester',
      'enabled': 1,
      'created_at': DateTime.now().millisecondsSinceEpoch,
    });
    expect(await repo.isUserAllowed(123), isTrue);
    expect(await repo.isUserAllowed(999), isFalse);

    final userId = await repo.appendContext(
      scope: privateScope,
      role: 'user',
      content: '你好',
    );
    await repo.appendContext(
      scope: privateScope,
      role: 'assistant',
      content: '你好呀',
    );
    final context = await repo.recentContext(privateScope);
    expect(context.length, 2);
    expect(context.first['role'], 'user');
    expect(context.last['content'], '你好呀');
    final before = await repo.recentContext(privateScope, beforeId: userId);
    expect(before, isEmpty);

    await repo.appendConversation(
      scope: groupScope,
      role: 'user',
      content: '群消息',
      onebotMessageId: 42,
      nickname: 'tester',
    );
    await repo.logToolCall(
      scope: privateScope,
      name: 'currentTime',
      arguments: '{}',
      result: '2026-01-01 00:00:00',
      isError: false,
    );
    final calls = await repo.recentToolCalls(privateScope);
    expect(calls.length, 1);
    expect(calls.first['tool_name'], 'currentTime');

    expect(
      await repo.rememberPrivate(
        scope: privateScope,
        content: '用户喜欢猫',
      ),
      isTrue,
    );
    expect(
      await repo.rememberPrivate(
        scope: privateScope,
        content: '用户喜欢猫',
      ),
      isFalse,
    );
    expect(await repo.recallText(privateScope, '猫'), contains('用户喜欢猫'));
    expect(await repo.recallText(groupScope, '猫'), isNot(contains('用户喜欢猫')));

    expect(
      await repo.rememberShared(
        scope: groupScope,
        content: '这个群的暗号是芝麻开门',
      ),
      isTrue,
    );
    expect(
      await repo.recallText(groupScope, '暗号'),
      contains('芝麻开门'),
    );
  });
}
