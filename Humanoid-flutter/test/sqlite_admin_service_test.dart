import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/database/sqlite_admin_service.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('SQLite 表浏览、CRUD 与 CSV 导入', () async {
    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    final db = DatabaseUtil.instance.db;
    await db.execute('''
      CREATE TABLE test_items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        age INTEGER,
        note TEXT
      )
    ''');

    final service = SqliteAdminService();
    final tables = await service.listTables();
    expect(tables, contains('test_items'));

    final columns = await service.tableColumns('test_items');
    expect(
      columns.map((column) => column.name),
      containsAll(['id', 'name', 'age', 'note']),
    );
    expect(columns.first.isPrimaryKey, isTrue);

    await service.insertRow('test_items', {
      'name': 'Alice',
      'age': 30,
      'note': 'hello',
    });
    var page = await service.fetchRows('test_items');
    expect(page.total, 1);
    expect(page.rows.first['name'], 'Alice');

    final row = page.rows.first;
    final key = SqliteRowKey.fromRow(page.columns, row);
    await service.updateRow('test_items', {
      'name': 'Alice2',
      'age': 31,
    }, key);
    page = await service.fetchRows('test_items');
    expect(page.rows.first['name'], 'Alice2');
    expect(page.rows.first['age'], 31);

    final result = await service.importCsv(
      table: 'test_items',
      content: 'name,age,note\nBob,25,\nCarol,28,hi\n',
      options: const CsvImportOptions(),
    );
    expect(result.imported, 2);
    expect(result.skipped, 0);

    page = await service.fetchRows('test_items');
    expect(page.total, 3);
    final bob = page.rows.firstWhere((item) => item['name'] == 'Bob');
    expect(bob['note'], isNull);

    final replaceResult = await service.importCsv(
      table: 'test_items',
      content: 'id,name,age,note\n1,Alice3,32,updated\n',
      options: const CsvImportOptions(replaceOnConflict: true),
    );
    expect(replaceResult.imported, 1);
    page = await service.fetchRows('test_items');
    final alice = page.rows.firstWhere((item) => item['id'] == 1);
    expect(alice['name'], 'Alice3');
    expect(alice['note'], 'updated');

    await service.deleteRow(
      'test_items',
      SqliteRowKey.fromRow(page.columns, alice),
    );
    page = await service.fetchRows('test_items');
    expect(page.total, 2);
  });
}
