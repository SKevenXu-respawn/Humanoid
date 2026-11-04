import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/pages/management/management_page.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  testWidgets('管理页展示好友申请、任务、提醒和视频标签', (tester) async {
    tester.view.physicalSize = const Size(1600, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.runAsync(() async {
      await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    });
    addTearDown(DatabaseUtil.instance.close);

    await tester.pumpWidget(const MaterialApp(home: ManagementPage()));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 300)),
    );
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.text('管理'), findsOneWidget);
    expect(find.text('好友申请 0'), findsOneWidget);
    expect(find.text('任务 0'), findsOneWidget);
    expect(find.text('提醒 0'), findsOneWidget);
    expect(find.text('视频 0'), findsOneWidget);
    expect(find.text('暂无好友申请'), findsOneWidget);
  });
}
