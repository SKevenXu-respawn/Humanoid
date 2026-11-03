import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/pages/logs/logs_page.dart';

void main() {
  testWidgets('日志页渲染终端控制区', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: LogsPage()));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('终端日志'), findsOneWidget);
    expect(find.text('启动'), findsOneWidget);
    expect(find.text('重启'), findsOneWidget);
    expect(find.text('关闭'), findsOneWidget);
    expect(find.byType(TextField), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
