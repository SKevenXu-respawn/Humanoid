import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/terminal/terminal_util.dart';

void main() {
  test('解析登录二维码日志并转换本地路径', () {
    final esc = String.fromCharCode(27);
    const localPath =
        '/mnt/d/PROJECTS/serverProject/Humanoid/Humanoid-flutter/llonebot/bin/llbot/data/temp/login-qrcode.png';
    final fileLine = '$esc[32m[I]$esc[0m qq-protocol 二维码文件已保存: $localPath';
    final parsedPath = TerminalUtil.parseQrCodeLine(fileLine);
    expect(parsedPath.path, localPath);
    expect(parsedPath.url, isNull);

    const url = 'https://api.2dcode.biz/v1/create-qr-code?data=abc%3D%3D';
    final urlLine = '$esc[32m[I]$esc[0m qq-protocol 或浏览器打开二维码网址: $url';
    final parsedUrl = TerminalUtil.parseQrCodeLine(urlLine);
    expect(parsedUrl.url, url);

    final info = QrCodeInfo(
      localPath: parsedPath.path,
      url: parsedUrl.url,
      createdAt: DateTime(2026, 1, 1),
    );
    expect(
      info.windowsPath,
      r'D:\PROJECTS\serverProject\Humanoid\Humanoid-flutter\llonebot\bin\llbot\data\temp\login-qrcode.png',
    );
  });

  test('识别 QQ 登录成功日志', () {
    final esc = String.fromCharCode(27);
    expect(
      TerminalUtil.isLoginSuccessLog(
        '$esc[32m[PMHQ login]$esc[0m listener.onLoginState 1',
      ),
      isTrue,
    );
    expect(
      TerminalUtil.isLoginSuccessLog('[PMHQ login] listener.onLoginState 0'),
      isFalse,
    );
  });

  test('识别 WebUI 启动日志', () {
    final esc = String.fromCharCode(27);
    expect(
      TerminalUtil.parseWebUiLine(
        '$esc[32m[I]$esc[0m webui-server Webui 服务器已启动 127.0.0.1:3080',
      ),
      'http://127.0.0.1:3080',
    );
    expect(
      TerminalUtil.parseWebUiLine(
        '[I] webui-server Webui 服务器已启动 http://127.0.0.1:3080',
      ),
      'http://127.0.0.1:3080',
    );
    expect(TerminalUtil.parseWebUiLine('other log'), isNull);
  });

  test('终端单例启动、执行命令、重启、关闭', () async {
    final terminal = TerminalUtil.instance;
    addTearDown(terminal.stop);

    await terminal.start();
    expect(terminal.statusNotifier.value, TerminalStatus.running);
    final firstPid = terminal.pid;
    expect(firstPid, isNotNull);

    await terminal.start();
    expect(terminal.pid, firstPid);

    await terminal.sendCommand('echo terminal-ok');
    await terminal.sendCommand('echo 终端中文');
    await _waitFor(() {
      final stdout = terminal.logsNotifier.value
          .where((entry) => entry.type == TerminalLogType.stdout)
          .toList();
      return stdout.any((entry) => entry.text.contains('terminal-ok')) &&
          stdout.any((entry) => entry.text.contains('终端中文'));
    });
    expect(
      terminal.logsNotifier.value.any(
        (entry) =>
            entry.type == TerminalLogType.stdout &&
            entry.text.contains('terminal-ok'),
      ),
      isTrue,
    );
    expect(
      terminal.logsNotifier.value.any(
        (entry) =>
            entry.type == TerminalLogType.stdout &&
            entry.text.contains('终端中文'),
      ),
      isTrue,
    );

    await terminal.restart();
    expect(terminal.statusNotifier.value, TerminalStatus.running);
    expect(terminal.pid, isNotNull);
    expect(terminal.pid, isNot(firstPid));

    await terminal.stop();
    expect(terminal.statusNotifier.value, TerminalStatus.stopped);
    expect(terminal.pid, isNull);
  }, skip: !Platform.isWindows);
}

Future<void> _waitFor(bool Function() predicate) async {
  final deadline = DateTime.now().add(const Duration(seconds: 8));
  while (DateTime.now().isBefore(deadline)) {
    if (predicate()) {
      return;
    }
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
}
