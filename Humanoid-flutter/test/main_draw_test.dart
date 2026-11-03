import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/models/mainDrawModels.dart';
import 'package:humanoid_flutter/pages/configs/secret_highlight_controller.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:humanoid_flutter/widgets/mainDraw.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  testWidgets('启动前检测缺失密钥并跳转配置页高亮', (tester) async {
    tester.view.physicalSize = const Size(1600, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.runAsync(() async {
      await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    });
    addTearDown(DatabaseUtil.instance.close);
    SecretHighlightController.clear();
    addTearDown(SecretHighlightController.clear);

    MainDrawModels? selected;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Row(
            children: [
              MainDraw(callback: (item) => selected = item),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('启动'), findsOneWidget);
    expect(find.text('重启'), findsNothing);
    expect(find.text('停止'), findsNothing);

    await tester.tap(find.text('启动'));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 400)),
    );
    await tester.pump(const Duration(milliseconds: 500));

    expect(selected?.name, '配置');
    expect(
      SecretHighlightController.keysNotifier.value,
      containsAll(<String>['deepseek_key', 'llonebot_key', 'speech_key']),
    );
    expect(find.text('启动'), findsOneWidget);
    expect(find.text('重启'), findsNothing);
    expect(find.text('停止'), findsNothing);
  });
}
