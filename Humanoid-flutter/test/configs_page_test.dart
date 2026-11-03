import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/pages/configs/configs_page.dart';
import 'package:humanoid_flutter/pages/configs/secret_highlight_controller.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  testWidgets('配置页读取并显示预设密钥，缺失项高亮', (tester) async {
    await tester.runAsync(() async {
      await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    });
    addTearDown(DatabaseUtil.instance.close);
    SecretHighlightController.highlight(['deepseek_key']);
    addTearDown(SecretHighlightController.clear);

    await tester.pumpWidget(const MaterialApp(home: ConfigsPage()));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 300)),
    );
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('DeepSeek Key'), findsOneWidget);
    expect(find.text('LLOneBot Key'), findsOneWidget);
    expect(find.text('语音服务 Key'), findsOneWidget);
    expect(find.text('获取 Key'), findsNWidgets(3));
    expect(find.byType(TextField), findsNWidgets(3));
    expect(find.text('请先填写缺失的密钥'), findsOneWidget);
    expect(find.text('必填'), findsOneWidget);

    await tester.enterText(find.byType(TextField).first, 'sk-test');
    await tester.pump();
    expect(find.text('请先填写缺失的密钥'), findsNothing);
    expect(find.text('必填'), findsNothing);
  });
}
