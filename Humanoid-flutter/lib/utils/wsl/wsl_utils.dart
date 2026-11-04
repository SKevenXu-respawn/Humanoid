import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;

import '../../config/AppEnum.dart';
import '../terminal/terminal_util.dart';

class WslUtils {
  WslUtils._();

  static final WslUtils instance = WslUtils._();

  final ValueNotifier<WslStatus> statusNotifier = ValueNotifier(
    WslStatus.uninstall,
  );
  final ValueNotifier<String?> networkingModeNotifier =
      ValueNotifier<String?>(null);

  static String? detectedUbuntuDistro;

  static const _tag = '[WSL]';

  static void _log(String message) {
    debugPrint('$_tag $message');
    TerminalUtil.instance.appendBotLog(message);
  }

  Future<void> init() async {
    _log('WslUtils 正在初始化...');
    await ensureMirroredNetworking();
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

  Future<void> ensureMirroredNetworking({bool forceRestart = false}) async {
    final mode = await _queryNetworkingMode();
    networkingModeNotifier.value = mode;
    if (mode == 'mirrored') {
      try {
        final marker = _mirrorFailedMarker();
        if (marker != null && await marker.exists()) {
          await marker.delete();
        }
      } catch (_) {
        // ignore marker cleanup errors
      }
      _log('WSL 网络模式: mirrored');
      return;
    }
    if (mode == null) {
      _log('无法检测 WSL 网络模式，跳过镜像网络配置');
      return;
    }

    final configFile = _wslConfigFile();
    final marker = _mirrorFailedMarker();
    if (configFile == null) {
      _log('无法定位 .wslconfig，跳过镜像网络配置');
      return;
    }
    if (!forceRestart && marker != null && await marker.exists()) {
      _log('WSL 镜像网络上次切换未生效，跳过自动重启（可在 WSL 环境页手动重试）');
      return;
    }

    final original = await configFile.exists()
        ? await configFile.readAsString()
        : null;
    final hasMirrorConfig = original != null &&
        RegExp(
          r'^\s*networkingMode\s*=\s*mirrored\s*$',
          caseSensitive: false,
          multiLine: true,
        ).hasMatch(original);

    if (!hasMirrorConfig) {
      final updated = applyMirroredConfig(original ?? '');
      try {
        await configFile.writeAsString(updated, flush: true);
        _log('已写入 .wslconfig: networkingMode=mirrored');
      } catch (e) {
        _log('写入 .wslconfig 失败: $e');
        return;
      }
    } else {
      _log('WSL 配置已是 mirrored，正在重启 WSL 使其生效...');
    }

    _log('正在重启 WSL...');
    await Process.run('wsl', ['--shutdown']);
    await Future<void>.delayed(const Duration(seconds: 3));

    final verify = await _queryNetworkingMode();
    networkingModeNotifier.value = verify;
    if (verify == 'mirrored') {
      try {
        if (marker != null && await marker.exists()) {
          await marker.delete();
        }
      } catch (_) {
        // ignore marker cleanup errors
      }
      _log('WSL 镜像网络已启用');
      return;
    }

    if (marker != null) {
      try {
        await marker.writeAsString(DateTime.now().toIso8601String());
      } catch (_) {
        // ignore marker write errors
      }
    }

    if (verify == null) {
      _log('重启后无法检测 WSL，回滚 .wslconfig');
      try {
        if (original == null) {
          await configFile.delete();
        } else {
          await configFile.writeAsString(original, flush: true);
        }
      } catch (e) {
        _log('回滚 .wslconfig 失败: $e');
      }
      await Process.run('wsl', ['--shutdown']);
      return;
    }

    _log('WSL 镜像网络未生效（当前=$verify），配置已保留；可手动重试');
  }

  static File? _mirrorFailedMarker() {
    final home =
        Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'];
    if (home == null || home.trim().isEmpty) {
      return null;
    }
    return File(p.join(home, '.humanoid_wsl_mirror_failed'));
  }

  static Future<String?> _queryNetworkingMode() async {
    try {
      final result = await Process.run(
        'wsl',
        const ['--exec', 'wslinfo', '--networking-mode'],
        stdoutEncoding: utf8,
        stderrEncoding: utf8,
      );
      if (result.exitCode != 0) {
        return null;
      }
      final output = result.stdout.toString().trim().toLowerCase();
      return output.isEmpty ? null : output;
    } catch (_) {
      return null;
    }
  }

  static File? _wslConfigFile() {
    final home =
        Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'];
    if (home == null || home.trim().isEmpty) {
      return null;
    }
    return File(p.join(home, '.wslconfig'));
  }

  static String applyMirroredConfig(String input) {
    var content = input.replaceAll('\r\n', '\n');
    if (content.startsWith(String.fromCharCode(0xFEFF))) {
      content = content.substring(1);
    }
    final lines = content.split('\n');
    var sectionIndex = -1;
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].trim().toLowerCase() == '[wsl2]') {
        sectionIndex = i;
        break;
      }
    }

    if (sectionIndex == -1) {
      final prefix = content.trim().isEmpty ? '' : '${content.trimRight()}\n\n';
      return '$prefix[wsl2]\nnetworkingMode=mirrored\n';
    }

    var sectionEnd = lines.length;
    for (var i = sectionIndex + 1; i < lines.length; i++) {
      if (lines[i].trim().startsWith('[')) {
        sectionEnd = i;
        break;
      }
    }

    var replaced = false;
    for (var i = sectionIndex + 1; i < sectionEnd; i++) {
      if (RegExp(
        r'^\s*networkingMode\s*=',
        caseSensitive: false,
      ).hasMatch(lines[i])) {
        lines[i] = 'networkingMode=mirrored';
        replaced = true;
        break;
      }
    }
    if (!replaced) {
      lines.insert(sectionIndex + 1, 'networkingMode=mirrored');
    }
    return lines.join('\n');
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
