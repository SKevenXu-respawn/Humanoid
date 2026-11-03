import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import '../../config/AppEnum.dart';

class WslUtils {
  WslUtils._();

  static final WslUtils instance = WslUtils._();

  final ValueNotifier<WslStatus> statusNotifier = ValueNotifier(
    WslStatus.uninstall,
  );

  static String? detectedUbuntuDistro;

  static const _tag = '[WSL]';

  static void _log(String message) => debugPrint('$_tag $message');

  Future<void> init() async {
    _log('WslUtils 正在初始化...');
    final status = await checkInstallStatus();
    statusNotifier.value = status;
    _log('WslUtils 初始化完成，状态: ${status.title}');
  }

  Future<void> refreshStatus() async {
    final status = await checkInstallStatus();
    statusNotifier.value = status;
  }

  Future<WslStatus> checkInstallStatus() async {
    _log('开始检测 WSL 安装状态');
    try {
      final wslPath = '${Platform.environment['SystemRoot']}\\System32\\wsl.exe';
      final isExist = await File(wslPath).exists();
      _log('wsl.exe 存在: $isExist，路径: $wslPath');

      if (!isExist) {
        _log('检测结果: 未安装 WSL');
        detectedUbuntuDistro = null;
        return WslStatus.uninstall;
      }

      final status = await checkStatus();
      _log('检测结果: ${status.title}');
      return status;
    } catch (e) {
      _log('检测安装状态失败: $e');
      detectedUbuntuDistro = null;
      return WslStatus.uninstall;
    }
  }

  Future<WslStatus> requestInstallWithAdmin() async {
    _log('请求管理员权限执行 wsl --install');
    try {
      await Process.start('powershell', [
        '-Command',
        'Start-Process wsl -ArgumentList "--install" -Verb RunAs',
      ]);
      _log('已发起 WSL 安装请求，请在弹出窗口中完成安装并重启');
      return WslStatus.install;
    } catch (e) {
      _log('提权或执行失败: $e');
      return WslStatus.uninstall;
    }
  }

  Future<WslStatus> requestInstallUbuntuWithAdmin() async {
    _log('请求管理员权限执行 wsl --install -d Ubuntu');
    try {
      await Process.start('powershell', [
        '-Command',
        'Start-Process wsl -ArgumentList "--install","-d","Ubuntu" -Verb RunAs',
      ]);
      _log('已发起 Ubuntu 安装请求，请在弹出窗口完成安装');
      return WslStatus.install;
    } catch (e) {
      _log('提权或执行失败: $e');
      return WslStatus.uninstall;
    }
  }

  static Future<WslStatus> checkStatus() async {
    try {
      final wslPath = '${Platform.environment['SystemRoot']}\\System32\\wsl.exe';
      if (!File(wslPath).existsSync()) {
        _log('wsl.exe 不存在，标记为未安装 WSL');
        detectedUbuntuDistro = null;
        return WslStatus.uninstall;
      }

      _log('执行命令: wsl -l -q');
      final result = await Process.run('wsl', ['-l', '-q']);
      final distros = _decodeOutput(result.stdout);
      _log(
        'wsl -l -q 退出码=${result.exitCode}，'
        '发行版=${distros.isEmpty ? '(空)' : distros.replaceAll(RegExp(r'\r?\n'), ', ')}',
      );

      if (result.exitCode != 0) {
        _log('wsl -l -q 执行失败，标记为未安装 WSL');
        detectedUbuntuDistro = null;
        return WslStatus.uninstall;
      }

      final ubuntu = findUbuntuDistro(distros);
      if (ubuntu == null) {
        _log('未检测到 Ubuntu 发行版');
        detectedUbuntuDistro = null;
        return WslStatus.install;
      }

      detectedUbuntuDistro = ubuntu;
      _log('检测到 Ubuntu 发行版: $ubuntu');
      _log('执行命令: wsl -d $ubuntu -e echo ping');
      final pingResult = await Process.run(
        'wsl',
        ['-d', ubuntu, '-e', 'echo', 'ping'],
      );
      final status = pingResult.exitCode == 0
          ? WslStatus.run
          : WslStatus.stop;
      _log(
        'wsl -d $ubuntu -e echo ping 退出码=${pingResult.exitCode}，'
        '检测结果: ${status.title}',
      );
      return status;
    } catch (e) {
      _log('检查 WSL 状态异常: $e');
      detectedUbuntuDistro = null;
      return WslStatus.uninstall;
    }
  }

  static String? findUbuntuDistro(String output) {
    final cleaned = _stripInvisible(output);
    for (final line in cleaned.split(RegExp(r'\r?\n'))) {
      final name = line.trim();
      if (name.toLowerCase().startsWith('ubuntu')) {
        return name;
      }
    }
    return null;
  }

  static String _decodeOutput(dynamic output) {
    String text;
    if (output is List<int>) {
      text = utf8.decode(output, allowMalformed: true);
    } else {
      text = output?.toString() ?? '';
    }
    return _stripInvisible(text);
  }

  static String _stripInvisible(String text) {
    final buffer = StringBuffer();
    for (final rune in text.runes) {
      if (rune == 0x0000 || rune == 0xFEFF) {
        continue;
      }
      buffer.writeCharCode(rune);
    }
    return buffer.toString().trim();
  }
}
