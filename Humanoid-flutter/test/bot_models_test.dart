import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';

void main() {
  test('解析 OneBot 群聊事件', () {
    final event = BotEvent.parse({
      'post_type': 'message',
      'message_type': 'group',
      'self_id': 1,
      'user_id': 2,
      'group_id': 3,
      'message_id': 4,
      'message_seq': 5,
      'time': 1700000000,
      'sender': {'nickname': 'tester'},
      'message': [
        {
          'type': 'reply',
          'data': {'id': '99'},
        },
        {
          'type': 'text',
          'data': {'text': '你好'},
        },
        {
          'type': 'image',
          'data': {'url': 'https://example.com/a.png'},
        },
      ],
    });

    expect(event, isNotNull);
    expect(event!.postType, 'message');
    expect(event.isGroup, isTrue);
    expect(event.userId, 2);
    expect(event.groupId, 3);
    expect(event.text, '你好');
    expect(event.imageRefs, ['https://example.com/a.png']);
    expect(event.replyMessageId, 99);
    expect(event.nickname, 'tester');
    expect(event.scope!.key, '2:group:3');
  });

  test('BotScope 元数据往返', () {
    const scope = BotScope(userId: 42, messageType: 'private');
    final restored = BotScope.fromMetadata(scope.toMetadata());
    expect(restored, isNotNull);
    expect(restored!.userId, 42);
    expect(restored.isGroup, isFalse);
    expect(restored.scopeGroupId, 0);
    expect(restored.key, '42:private:0');
  });
}
