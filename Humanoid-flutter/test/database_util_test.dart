import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/config/AppEnum.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('初始化数据库并读写配置与密钥', () async {
    final util = DatabaseUtil.instance;
    addTearDown(util.close);

    expect(util.statusNotifier.value, DatabaseStatus.uninitialized);

    await util.init(path: inMemoryDatabasePath);
    expect(util.isInitialized, isTrue);
    expect(util.statusNotifier.value, DatabaseStatus.ready);

    expect(
      await util.missingSecretKeys(),
      containsAll(<String>['deepseek_key', 'llonebot_key', 'speech_key']),
    );

    await util.setSetting('base_url', 'http://127.0.0.1:8080');
    expect(await util.getSetting('base_url'), 'http://127.0.0.1:8080');

    await util.deleteSetting('base_url');
    expect(await util.getSetting('base_url'), isNull);

    final secrets = await util.listSecrets();
    expect(
      secrets.map((row) => row['secret_key']),
      containsAll(<String>['deepseek_key', 'llonebot_key', 'speech_key']),
    );
    expect(
      secrets.every((row) => row['secret_value'] == null),
      isTrue,
    );
    expect(
      secrets.firstWhere((row) => row['secret_key'] == 'deepseek_key')['secret_url'],
      'https://platform.deepseek.com/api_keys',
    );
    expect(
      secrets.firstWhere((row) => row['secret_key'] == 'llonebot_key')['secret_url'],
      'https://auth.luckylillia.com/tokens',
    );
    expect(
      secrets.firstWhere((row) => row['secret_key'] == 'speech_key')['secret_url'],
      contains('doubao-seed-tts-2-0'),
    );

    await util.saveSecrets({'deepseek_key': 'sk-test'});
    final updated = await util.listSecrets();
    expect(
      updated.firstWhere((row) => row['secret_key'] == 'deepseek_key')['secret_value'],
      'sk-test',
    );
    expect(await util.getSecretValue('deepseek_key'), 'sk-test');
    expect(await util.getSecretValue('llonebot_key'), isNull);
    expect(
      await util.missingSecretKeys(),
      isNot(contains('deepseek_key')),
    );

    await util.saveSecrets({
      'deepseek_key': 'sk-test',
      'llonebot_key': 'll-test',
      'speech_key': 'sp-test',
    });
    expect(await util.missingSecretKeys(), isEmpty);
    expect(await util.getSecretValue('llonebot_key'), 'll-test');

    await util.close();
    expect(util.statusNotifier.value, DatabaseStatus.uninitialized);
  });

  test('旧数据库升级后补齐 secret_url 且保留原密钥', () async {
    final dir = await Directory.systemTemp.createTemp('humanoid_db_test');
    addTearDown(() async {
      await DatabaseUtil.instance.close();
      if (await dir.exists()) {
        await dir.delete(recursive: true);
      }
    });
    final path = p.join(dir.path, 'humanoid.db');

    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    final oldDb = await databaseFactory.openDatabase(
      path,
      options: OpenDatabaseOptions(
        version: 2,
        onCreate: (db, version) async {
          await db.execute(
            'CREATE TABLE app_setting (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER NOT NULL)',
          );
          await db.execute('''
            CREATE TABLE bot_secret (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              secret_key TEXT NOT NULL UNIQUE,
              secret_name TEXT,
              secret_value TEXT,
              updated_at INTEGER NOT NULL
            )
          ''');
          await db.insert('bot_secret', {
            'secret_key': 'deepseek_key',
            'secret_name': 'DeepSeek Key',
            'secret_value': 'sk-old',
            'updated_at': 0,
          });
          await db.insert('bot_secret', {
            'secret_key': 'llonebot_key',
            'secret_name': 'LLOneBot Key',
            'secret_value': null,
            'updated_at': 0,
          });
          await db.insert('bot_secret', {
            'secret_key': 'speech_key',
            'secret_name': '语音服务 Key',
            'secret_value': null,
            'updated_at': 0,
          });
        },
      ),
    );
    await oldDb.close();

    await DatabaseUtil.instance.init(path: path);
    final secrets = await DatabaseUtil.instance.listSecrets();

    final deepseek =
        secrets.firstWhere((row) => row['secret_key'] == 'deepseek_key');
    expect(deepseek['secret_value'], 'sk-old');
    expect(deepseek['secret_url'], 'https://platform.deepseek.com/api_keys');
    expect(
      secrets.firstWhere((row) => row['secret_key'] == 'speech_key')['secret_url'],
      contains('doubao-seed-tts-2-0'),
    );
  });
}
