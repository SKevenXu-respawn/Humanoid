import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/bot_skill_service.dart';
import 'package:path/path.dart' as p;

void main() {
  test('按 QQ 注册、列出和读取技能', () async {
    final dir = await Directory.systemTemp.createTemp('humanoid_skills_');
    addTearDown(() => dir.delete(recursive: true));
    final service = BotSkillService(
      dirOverride: (userId) => Directory(p.join(dir.path, '$userId')),
    );

    await service.register(
      userId: 10001,
      name: 'hello',
      description: '打招呼技能',
      content: '先向用户问好，然后询问需求。',
    );

    final skills = await service.list(10001);
    expect(skills.length, 1);
    expect(skills.first.name, 'hello');
    expect(skills.first.description, '打招呼技能');
    expect(await service.list(10002), isEmpty);

    final skill = await service.read(10001, 'hello');
    expect(skill, isNotNull);
    expect(skill!.content, contains('先向用户问好'));

    final brief = await service.brief(userId: 10001);
    expect(brief, contains('hello'));
    expect(brief, contains('打招呼技能'));

    await expectLater(
      service.register(
        userId: 10001,
        name: 'bad name!',
        description: 'x',
        content: 'x',
      ),
      throwsA(isA<ArgumentError>()),
    );
  });
}
