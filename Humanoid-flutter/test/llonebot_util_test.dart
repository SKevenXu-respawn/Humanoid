import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:humanoid_flutter/utils/llonebot/llonebot_util.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('Windows 路径转换为 WSL 路径并生成启动命令', () {
    expect(
      LLOneBotUtil.toWslPath(
        r'D:\PROJECTS\serverProject\Humanoid\Humanoid-flutter\llonebot',
      ),
      '/mnt/d/PROJECTS/serverProject/Humanoid/Humanoid-flutter/llonebot',
    );
    expect(
      LLOneBotUtil.instance.wslStartCommand,
      contains('./start.sh --headed --shell'),
    );
    expect(
      LLOneBotUtil.instance.wslStartCommand,
      contains('-d Ubuntu'),
    );
    expect(
      LLOneBotUtil.instance.authTokenPath.replaceAll('\\', '/'),
      endsWith('/llonebot/bin/llbot/data/auth_token.txt'),
    );
    expect(
      LLOneBotUtil.instance.defaultConfigPath.replaceAll('\\', '/'),
      endsWith('/llonebot/bin/llbot/default_config.json'),
    );
  });

  test('配置机器人 QQ 后启动命令带 --qq 快速登录参数', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    await DatabaseUtil.instance.setSetting('bot_qq', '123456789');

    final command = await LLOneBotUtil.instance.buildWslStartCommand();
    expect(command, contains('--headed --shell --qq=123456789'));
  });

  test('写入 LLOneBot Auth Token', () async {
    final dir = await Directory.systemTemp.createTemp('humanoid_llonebot_');
    addTearDown(() => dir.delete(recursive: true));

    final path = p.join(dir.path, 'bin', 'llbot', 'data', 'auth_token.txt');
    await LLOneBotUtil.instance.writeAuthToken('  token-value  ', path: path);

    expect(await File(path).readAsString(), 'token-value');
  });

  test('校验并写入默认配置', () async {
    final dir = await Directory.systemTemp.createTemp('humanoid_llonebot_cfg_');
    addTearDown(() => dir.delete(recursive: true));
    final path = p.join(dir.path, 'default_config.json');

    await LLOneBotUtil.instance.writeDefaultConfig(
      '{"webui":{"enable":true}}',
      path: path,
    );
    final written = await File(path).readAsString();
    expect(written, contains('"enable": true'));
    expect(written, contains('\n'));

    await expectLater(
      LLOneBotUtil.instance.writeDefaultConfig('not json', path: path),
      throwsA(isA<FormatException>()),
    );
  });
}
