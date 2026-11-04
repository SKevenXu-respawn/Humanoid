import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:archive/archive_io.dart';
import 'package:device_inspector/device_inspector.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;

import '../../config/AppEnum.dart';
import '../database_util.dart';
import '../wsl/wsl_utils.dart';

enum LLOneBotInstallStage { downloading, extracting, done, failed }

class LLOneBotProgress {
  final LLOneBotInstallStage stage;
  final double? value;
  final String message;

  const LLOneBotProgress({
    required this.stage,
    this.value,
    this.message = '',
  });
}

class LLOneBotUtil {
  LLOneBotUtil._();

  static final LLOneBotUtil instance = LLOneBotUtil._();

  static const _tag = '[LLOneBot]';
  static const _x64Url =
      'https://github.com/LLOneBot/LuckyLilliaBot/releases/download/v8.3.2/LuckyLillia-CLI-linux-x64.zip';
  static const _arm64Url =
      'https://github.com/LLOneBot/LuckyLilliaBot/releases/download/v8.3.2/LuckyLillia-CLI-linux-arm64.zip';

  final ValueNotifier<LLOneBotStatus> statusNotifier =
      ValueNotifier(LLOneBotStatus.checking);

  bool _checking = false;
  bool _installing = false;

  String get installPath => p.join(Directory.current.path, 'llonebot');

  bool get isInstalled => Directory(installPath).existsSync();

  String get authTokenPath =>
      p.join(installPath, 'bin', 'llbot', 'data', 'auth_token.txt');

  String get defaultConfigPath =>
      p.join(installPath, 'bin', 'llbot', 'default_config.json');

  Future<String> readDefaultConfig({String? path}) async {
    final file = File(path ?? defaultConfigPath);
    if (!await file.exists()) {
      throw StateError('默认配置文件不存在: ${file.path}');
    }
    return file.readAsString();
  }

  Future<void> writeDefaultConfig(String content, {String? path}) async {
    final decoded = jsonDecode(content);
    if (decoded is! Map) {
      throw const FormatException('默认配置必须是 JSON 对象');
    }
    final pretty = JsonEncoder.withIndent('  ').convert(decoded);
    final file = File(path ?? defaultConfigPath);
    await file.parent.create(recursive: true);
    await file.writeAsString(pretty, flush: true);
    _log('默认配置已更新: ${file.path}');
  }

  Future<void> writeAuthToken(String token, {String? path}) async {
    final value = token.trim();
    if (value.isEmpty) {
      throw ArgumentError('LLOneBot Auth Token 不能为空');
    }
    final file = File(path ?? authTokenPath);
    await file.parent.create(recursive: true);
    await file.writeAsString(value, flush: true);
    _log('Auth Token 已写入: ${file.path}');
  }

  static String toWslPath(String windowsPath) {
    final path = windowsPath.replaceAll('\\', '/');
    if (path.length >= 2 && path[1] == ':') {
      return '/mnt/${path[0].toLowerCase()}${path.substring(2)}';
    }
    return path;
  }

  String get wslStartCommand => _buildWslStartCommand(null);

  Future<String> buildWslStartCommand() async {
    final qq = (await DatabaseUtil.instance.getSetting('bot_qq'))?.trim() ?? '';
    return _buildWslStartCommand(qq.isEmpty ? null : qq);
  }

  static String _buildWslStartCommand(String? qq) {
    final directory = toWslPath(LLOneBotUtil.instance.installPath);
    final distro = WslUtils.detectedUbuntuDistro ?? 'Ubuntu';
    final qqArg = qq == null || qq.isEmpty ? '' : ' --qq=$qq';
    return 'wsl -d $distro bash -lc "cd \'$directory\' && '
        'chmod +x start.sh && ./start.sh --headed --shell$qqArg"';
  }

  static void _log(String message) => debugPrint('$_tag $message');

  Future<void> checkStatus() async {
    if (_checking || _installing) {
      return;
    }
    _checking = true;
    statusNotifier.value = LLOneBotStatus.checking;
    try {
      final installed = isInstalled;
      statusNotifier.value =
          installed ? LLOneBotStatus.installed : LLOneBotStatus.notInstalled;
      _log('检测完成: ${statusNotifier.value.title}，目录: $installPath');
    } finally {
      _checking = false;
    }
  }

  Future<void> install({
    void Function(LLOneBotProgress progress)? onProgress,
  }) async {
    if (_installing) {
      return;
    }
    _installing = true;

    final tempZip = File(
      p.join(
        Directory.systemTemp.path,
        'lucky-lillia-${DateTime.now().millisecondsSinceEpoch}.zip',
      ),
    );

    try {
      statusNotifier.value = LLOneBotStatus.downloading;
      onProgress?.call(const LLOneBotProgress(
        stage: LLOneBotInstallStage.downloading,
        value: 0,
        message: '准备下载...',
      ));

      final url = await _resolveDownloadUrl();
      _log('开始下载: $url');
      await _download(url, tempZip, onProgress);

      statusNotifier.value = LLOneBotStatus.extracting;
      onProgress?.call(const LLOneBotProgress(
        stage: LLOneBotInstallStage.extracting,
        value: 0,
        message: '正在解压安装包',
      ));

      final target = Directory(installPath);
      await target.create(recursive: true);
      await _extract(tempZip.path, installPath, onProgress);

      statusNotifier.value = LLOneBotStatus.installed;
      onProgress?.call(const LLOneBotProgress(
        stage: LLOneBotInstallStage.done,
        value: 1,
        message: '安装完成',
      ));
      _log('安装完成: $installPath');
    } catch (e) {
      _log('安装失败: $e');
      statusNotifier.value = LLOneBotStatus.error;
      try {
        final target = Directory(installPath);
        if (target.existsSync()) {
          target.deleteSync(recursive: true);
        }
      } catch (cleanupError) {
        _log('清理未完成的安装目录失败: $cleanupError');
      }
      rethrow;
    } finally {
      _installing = false;
      try {
        if (await tempZip.exists()) {
          await tempZip.delete();
        }
      } catch (e) {
        _log('删除安装包失败: $e');
      }
    }
  }

  Future<void> reinstall({
    void Function(LLOneBotProgress progress)? onProgress,
  }) async {
    await deleteInstall();
    await install(onProgress: onProgress);
  }

  Future<void> deleteInstall() async {
    final target = Directory(installPath);
    if (await target.exists()) {
      await target.delete(recursive: true);
      _log('已删除: $installPath');
    }
    statusNotifier.value = LLOneBotStatus.notInstalled;
  }

  Future<String> _resolveDownloadUrl() async {
    try {
      final cpu = await DeviceInspector.getCpuInfo();
      final architecture = cpu.architecture;
      final isArm64 = architecture == CpuArchitecture.arm64 ||
          (architecture == CpuArchitecture.arm && cpu.is64Bit == true);
      _log('CPU 架构=${architecture?.name}，is64Bit=${cpu.is64Bit}，'
          '选择=${isArm64 ? 'arm64' : 'x64'}');
      return isArm64 ? _arm64Url : _x64Url;
    } catch (e) {
      _log('获取 CPU 信息失败，默认使用 x64: $e');
      return _x64Url;
    }
  }

  Future<void> _download(
    String url,
    File target,
    void Function(LLOneBotProgress progress)? onProgress,
  ) async {
    final dio = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(minutes: 20),
      ),
    );

    await target.parent.create(recursive: true);
    await dio.download(
      url,
      target.path,
      deleteOnError: true,
      options: Options(
        followRedirects: true,
        receiveTimeout: const Duration(minutes: 20),
      ),
      onReceiveProgress: (received, total) {
        final value = total > 0 ? received / total : null;
        final message = total > 0
            ? '${Converters.formatBytes(received, decimals: 1)} / '
                '${Converters.formatBytes(total, decimals: 1)}'
            : '已下载 ${Converters.formatBytes(received, decimals: 1)}';
        onProgress?.call(LLOneBotProgress(
          stage: LLOneBotInstallStage.downloading,
          value: value,
          message: message,
        ));
      },
    );
  }

  Future<void> _extract(
    String zipPath,
    String outputPath,
    void Function(LLOneBotProgress progress)? onProgress,
  ) async {
    final receivePort = ReceivePort();
    final errorPort = ReceivePort();
    final completer = Completer<void>();
    Isolate? isolate;

    receivePort.listen((message) {
      if (message is! Map) {
        return;
      }
      switch (message['type']) {
        case 'progress':
          final value = (message['value'] as num?)?.toDouble();
          onProgress?.call(LLOneBotProgress(
            stage: LLOneBotInstallStage.extracting,
            value: value,
            message: '正在解压安装包',
          ));
        case 'done':
          if (!completer.isCompleted) {
            completer.complete();
          }
        case 'error':
          if (!completer.isCompleted) {
            completer.completeError(
              StateError(message['message']?.toString() ?? '解压失败'),
            );
          }
      }
    });

    errorPort.listen((message) {
      if (!completer.isCompleted) {
        completer.completeError(StateError('解压进程异常: $message'));
      }
    });

    try {
      isolate = await Isolate.spawn<List<dynamic>>(
        _extractArchiveWorker,
        [receivePort.sendPort, zipPath, outputPath],
        onError: errorPort.sendPort,
      );
      await completer.future;
    } finally {
      receivePort.close();
      errorPort.close();
      isolate?.kill(priority: Isolate.immediate);
    }
  }
}

Future<void> _extractArchiveWorker(List<dynamic> args) async {
  final sendPort = args[0] as SendPort;
  final zipPath = args[1] as String;
  final outputPath = args[2] as String;

  try {
    final input = InputFileStream(zipPath);
    final archive = ZipDecoder().decodeStream(input);
    final total = archive.files
        .where((file) => file.isFile)
        .fold<int>(0, (sum, file) => sum + file.size);
    await input.close();

    var processed = 0;
    await extractFileToDisk(
      zipPath,
      outputPath,
      throwOnError: true,
      callback: (file) {
        if (!file.isFile) {
          return;
        }
        processed += file.size;
        final value =
            total > 0 ? (processed / total).clamp(0.0, 1.0).toDouble() : null;
        sendPort.send(<String, dynamic>{'type': 'progress', 'value': value});
      },
    );

    sendPort.send(<String, dynamic>{'type': 'done'});
  } catch (e) {
    sendPort.send(<String, dynamic>{'type': 'error', 'message': e.toString()});
  }
}
