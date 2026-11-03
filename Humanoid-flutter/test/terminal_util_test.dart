import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/terminal/terminal_util.dart';

void main() {
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
