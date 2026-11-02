import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('初始化数据库并读写配置', () async {
    final util = DatabaseUtil.instance;
    await util.init(path: inMemoryDatabasePath);
    addTearDown(util.close);

    expect(util.isInitialized, isTrue);
    await util.setSetting('base_url', 'http://127.0.0.1:8080');
    expect(await util.getSetting('base_url'), 'http://127.0.0.1:8080');

    await util.deleteSetting('base_url');
    expect(await util.getSetting('base_url'), isNull);
  });
}
