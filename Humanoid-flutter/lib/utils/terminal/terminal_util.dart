import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

enum TerminalStatus { stopped, starting, running, stopping, error }

enum TerminalLogType { command, stdout, stderr, system }

class TerminalLogEntry {
  final TerminalLogType type;
  final String text;

  const TerminalLogEntry(this.type, this.text);
}

class TerminalUtil {
  TerminalUtil._();

  static final TerminalUtil instance = TerminalUtil._();

  static const _tag = '[Terminal]';
  static const _maxLogs = 2000;

  final ValueNotifier<TerminalStatus> statusNotifier =
      ValueNotifier(TerminalStatus.stopped);
  final ValueNotifier<List<TerminalLogEntry>> logsNotifier =
      ValueNotifier(const <TerminalLogEntry>[]);

  Process? _process;
  IOSink? _stdin;
  Future<void> _queue = Future.value();

  bool get isRunning => _process != null;

  int? get pid => _process?.pid;

  static void _log(String message) => debugPrint('$_tag $message');

  Future<void> _serialize(Future<void> Function() action) {
    final next = _queue.then((_) => action());
    _queue = next.catchError((Object _) {});
    return next;
  }

  Future<void> start() => _serialize(_startInternal);

  Future<void> stop() => _serialize(_stopInternal);

  Future<void> restart() => _serialize(() async {
        await _stopInternal();
        await _startInternal();
      });

  Future<void> _startInternal() async {
    if (_process != null) {
      return;
    }

    statusNotifier.value = TerminalStatus.starting;
    _appendSystem('正在启动终端...');

    try {
      final process = await Process.start(
        'powershell.exe',
        const ['-NoLogo', '-NoProfile', '-NoExit', '-Command', '-'],
      );
      process.stdin.encoding = systemEncoding;

      _process = process;
      _stdin = process.stdin;
      _listen(process);
      process.stdin.writeln(
        r'[Console]::OutputEncoding = [System.Text.Encoding]::UTF8',
      );
      process.stdin.writeln(
        r'$OutputEncoding = [System.Text.Encoding]::UTF8',
      );
      await process.stdin.flush();
      statusNotifier.value = TerminalStatus.running;
      _appendSystem('终端已启动（PID ${process.pid}）');
      _log('终端已启动 pid=${process.pid}');
    } catch (e) {
      _process = null;
      _stdin = null;
      statusNotifier.value = TerminalStatus.error;
      _appendSystem('终端启动失败: $e');
      _log('终端启动失败: $e');
    }
  }

  void _listen(Process process) {
    process.stdout
        .transform(const Utf8Decoder(allowMalformed: true))
        .transform(const LineSplitter())
        .listen(
      (line) => _append(TerminalLogType.stdout, line),
      onError: (Object error) => _appendSystem('标准输出读取失败: $error'),
    );

    process.stderr
        .transform(const Utf8Decoder(allowMalformed: true))
        .transform(const LineSplitter())
        .listen(
      (line) => _append(TerminalLogType.stderr, line),
      onError: (Object error) => _appendSystem('错误输出读取失败: $error'),
    );

    unawaited(process.exitCode.then((code) {
      if (!identical(_process, process)) {
        return;
      }
      _process = null;
      _stdin = null;
      statusNotifier.value = TerminalStatus.stopped;
      _appendSystem('终端已退出（code=$code）');
      _log('终端已退出 code=$code');
    }));
  }

  Future<void> _stopInternal() async {
    final process = _process;
    if (process == null) {
      statusNotifier.value = TerminalStatus.stopped;
      return;
    }

    statusNotifier.value = TerminalStatus.stopping;
    _appendSystem('正在关闭终端...');
    _process = null;
    _stdin = null;

    process.kill();
    try {
      await process.exitCode.timeout(const Duration(seconds: 3));
    } catch (_) {
      try {
        if (Platform.isWindows) {
          await Process.run(
            'taskkill',
            ['/PID', '${process.pid}', '/T', '/F'],
          );
        } else {
          process.kill(ProcessSignal.sigkill);
        }
      } catch (e) {
        _log('强制结束终端失败: $e');
      }
    }

    statusNotifier.value = TerminalStatus.stopped;
    _appendSystem('终端已关闭');
    _log('终端已关闭 pid=${process.pid}');
  }

  Future<void> sendCommand(String command) async {
    final text = command.trim();
    if (text.isEmpty) {
      return;
    }

    final normalized = text.toLowerCase();
    if (normalized == 'clear' || normalized == 'cls') {
      clearLogs();
      return;
    }

    final stdin = _stdin;
    if (stdin == null) {
      _appendSystem('终端未启动');
      return;
    }

    _append(TerminalLogType.command, text);
    try {
      stdin.writeln(text);
      await stdin.flush();
    } catch (e) {
      _appendSystem('命令发送失败: $e');
    }
  }

  void clearLogs() {
    logsNotifier.value = const <TerminalLogEntry>[];
  }

  void _appendSystem(String text) {
    _append(TerminalLogType.system, text);
  }

  void _append(TerminalLogType type, String text) {
    if (text.isEmpty) {
      return;
    }

    final next = List<TerminalLogEntry>.from(logsNotifier.value)
      ..add(TerminalLogEntry(type, text));
    if (next.length > _maxLogs) {
      next.removeRange(0, next.length - _maxLogs);
    }
    logsNotifier.value = next;
  }
}
