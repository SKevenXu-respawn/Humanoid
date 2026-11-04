import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/pages/database/database_page.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  testWidgets('数据库页渲染表选择与数据区', (tester) async {
    await tester.runAsync(() async {
      await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    });
    addTearDown(DatabaseUtil.instance.close);

    await tester.pumpWidget(const MaterialApp(home: DatabasePage()));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 300)),
    );
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('数据表'), findsOneWidget);
    expect(find.text('刷新'), findsOneWidget);
    expect(find.text('新增行'), findsOneWidget);
    expect(find.text('导入 CSV'), findsOneWidget);
    expect(find.text('app_setting'), findsWidgets);
  });
}
