import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/pages/configs/bot_settings_page.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  testWidgets('机器人设置页展示连接、白名单和提示词区块', (tester) async {
    tester.view.physicalSize = const Size(1600, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.runAsync(() async {
      await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    });
    addTearDown(DatabaseUtil.instance.close);

    await tester.pumpWidget(const MaterialApp(home: BotSettingsPage()));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 400)),
    );
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('机器人设置'), findsOneWidget);
    expect(find.text('连接与模型'), findsOneWidget);
    expect(find.text('白名单'), findsOneWidget);
    expect(find.text('语音与识别'), findsOneWidget);
    expect(find.text('全局提示词'), findsOneWidget);
    expect(find.text('按 QQ 自定义系统提示词'), findsOneWidget);
  });
}
