import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/llonebot/llonebot_util.dart';
import 'package:path/path.dart' as p;

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
  });

  test('写入 LLOneBot Auth Token', () async {
    final dir = await Directory.systemTemp.createTemp('humanoid_llonebot_');
    addTearDown(() => dir.delete(recursive: true));

    final path = p.join(dir.path, 'bin', 'llbot', 'data', 'auth_token.txt');
    await LLOneBotUtil.instance.writeAuthToken('  token-value  ', path: path);

    expect(await File(path).readAsString(), 'token-value');
  });
}
