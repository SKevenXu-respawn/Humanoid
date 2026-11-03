import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/pages/home/home_page.dart';

void main() {
  testWidgets('首页渲染状态卡片与 LLOneBot 区块', (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: HomePage()));
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('WSL 运行环境'), findsOneWidget);
    expect(find.text('数据库状态'), findsOneWidget);
    expect(find.text('LLOneBot 运行环境'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
