import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

class DatabaseUtil {
  DatabaseUtil._();

  static final DatabaseUtil instance = DatabaseUtil._();

  static const _dbName = 'humanoid.db';
  static const _dbVersion = 1;

  Database? _db;

  bool get isInitialized => _db != null;

  Database get db {
    final database = _db;
    if (database == null) {
      throw StateError('数据库尚未初始化，请先调用 DatabaseUtil.instance.init()');
    }
    return database;
  }

  Future<void> init({String? path}) async {
    if (_db != null) {
      return;
    }

    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;

    final dbPath = path ?? await _defaultPath();
    _db = await databaseFactory.openDatabase(
      dbPath,
      options: OpenDatabaseOptions(
        version: _dbVersion,
        onConfigure: (database) => database.execute('PRAGMA foreign_keys = ON'),
        onCreate: _onCreate,
      ),
    );
  }

  Future<String> _defaultPath() async {
    final dir = await getApplicationSupportDirectory();
    return p.join(dir.path, _dbName);
  }

  Future<void> _onCreate(Database database, int version) async {
    await database.execute('''
      CREATE TABLE app_setting (
        key TEXT PRIMARY KEY,
        value TEXT,
        updated_at INTEGER NOT NULL
      )
    ''');
  }

  Future<String?> getSetting(String key) async {
    final rows = await db.query(
      'app_setting',
      columns: ['value'],
      where: 'key = ?',
      whereArgs: [key],
      limit: 1,
    );
    return rows.isEmpty ? null : rows.first['value'] as String?;
  }

  Future<void> setSetting(String key, String value) async {
    await db.insert(
      'app_setting',
      {
        'key': key,
        'value': value,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> deleteSetting(String key) async {
    await db.delete('app_setting', where: 'key = ?', whereArgs: [key]);
  }

  Future<void> close() async {
    await _db?.close();
    _db = null;
  }
}
