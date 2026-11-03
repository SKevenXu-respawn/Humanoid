import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../config/AppEnum.dart';

class DatabaseUtil {
  DatabaseUtil._();

  static final DatabaseUtil instance = DatabaseUtil._();

  static const _tag = '[DB]';
  static const _dbName = 'humanoid.db';
  static const _dbVersion = 3;

  static const _presetSecrets = <Map<String, String>>[
    {
      'key': 'deepseek_key',
      'name': 'DeepSeek Key',
      'url': 'https://platform.deepseek.com/api_keys',
    },
    {
      'key': 'llonebot_key',
      'name': 'LLOneBot Key',
      'url': 'https://auth.luckylillia.com/tokens',
    },
    {
      'key': 'speech_key',
      'name': '语音服务 Key',
      'url':
          'https://console.volcengine.com/ark/region:cn-beijing/model/detail?name=doubao-seed-tts-2-0',
    },
  ];

  final ValueNotifier<DatabaseStatus> statusNotifier =
      ValueNotifier(DatabaseStatus.uninitialized);

  Database? _db;
  Future<void>? _initializing;

  bool get isInitialized => _db != null;

  Database get db {
    final database = _db;
    if (database == null) {
      throw StateError('数据库尚未初始化，请先调用 DatabaseUtil.instance.init()');
    }
    return database;
  }

  static void _log(String message) => debugPrint('$_tag $message');

  Future<void> init({String? path}) {
    if (_db != null) {
      statusNotifier.value = DatabaseStatus.ready;
      return Future.value();
    }
    return _initializing ??= _open(path);
  }

  Future<void> _open(String? path) async {
    statusNotifier.value = DatabaseStatus.initializing;
    _log('开始初始化数据库');
    try {
      sqfliteFfiInit();
      databaseFactory = databaseFactoryFfi;

      final dbPath = path ?? await _defaultPath();
      _db = await databaseFactory.openDatabase(
        dbPath,
        options: OpenDatabaseOptions(
          version: _dbVersion,
          onConfigure: (database) => database.execute('PRAGMA foreign_keys = ON'),
          onCreate: _onCreate,
          onUpgrade: _onUpgrade,
        ),
      );
      await _ensureSchema(_db!);
      statusNotifier.value = DatabaseStatus.ready;
      _log('数据库初始化完成: $dbPath');
    } catch (e) {
      try {
        await _db?.close();
      } catch (_) {
        // ignore
      }
      _db = null;
      statusNotifier.value = DatabaseStatus.error;
      _log('数据库初始化失败: $e');
    } finally {
      _initializing = null;
    }
  }

  Future<void> refreshStatus() async {
    final database = _db;
    if (database == null) {
      await init();
      return;
    }

    try {
      await database.rawQuery('SELECT 1');
      await _ensureSchema(database);
      statusNotifier.value = DatabaseStatus.ready;
      _log('数据库连接正常');
    } catch (e) {
      _db = null;
      statusNotifier.value = DatabaseStatus.error;
      _log('数据库状态检查失败: $e');
    }
  }

  Future<String> _defaultPath() async {
    final dir = await getApplicationSupportDirectory();
    return p.join(dir.path, _dbName);
  }

  Future<void> _onCreate(Database database, int version) async {
    await _createAppSettingTable(database);
    await _createBotSecretTable(database);
    await _seedSecrets(database);
  }

  Future<void> _onUpgrade(Database database, int oldVersion, int newVersion) async {
    if (oldVersion < 2) {
      await _createBotSecretTable(database);
    }
    if (oldVersion < 3) {
      await _ensureSecretUrlColumn(database);
    }
  }

  Future<void> _ensureSchema(Database database) async {
    await _createBotSecretTable(database);
    await _ensureSecretUrlColumn(database);
    await _seedSecrets(database);
  }

  Future<void> _createAppSettingTable(Database database) async {
    await database.execute('''
      CREATE TABLE IF NOT EXISTS app_setting (
        key TEXT PRIMARY KEY,
        value TEXT,
        updated_at INTEGER NOT NULL
      )
    ''');
  }

  Future<void> _createBotSecretTable(Database database) async {
    await database.execute('''
      CREATE TABLE IF NOT EXISTS bot_secret (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        secret_key TEXT NOT NULL UNIQUE,
        secret_name TEXT,
        secret_value TEXT,
        secret_url TEXT,
        updated_at INTEGER NOT NULL
      )
    ''');
  }

  Future<void> _ensureSecretUrlColumn(Database database) async {
    final columns = await database.rawQuery('PRAGMA table_info(bot_secret)');
    if (columns.isEmpty) {
      return;
    }
    final hasSecretUrl = columns.any((column) => column['name'] == 'secret_url');
    if (!hasSecretUrl) {
      await database.execute('ALTER TABLE bot_secret ADD COLUMN secret_url TEXT');
      _log('已为 bot_secret 添加 secret_url 字段');
    }
  }

  Future<void> _seedSecrets(Database database) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    for (final secret in _presetSecrets) {
      final key = secret['key']!;
      final name = secret['name']!;
      final url = secret['url']!;

      await database.insert(
        'bot_secret',
        {
          'secret_key': key,
          'secret_name': name,
          'secret_value': null,
          'secret_url': url,
          'updated_at': now,
        },
        conflictAlgorithm: ConflictAlgorithm.ignore,
      );

      await database.update(
        'bot_secret',
        {'secret_url': url},
        where: 'secret_key = ? AND (secret_url IS NULL OR secret_url = ?)',
        whereArgs: [key, ''],
      );
    }
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

  Future<List<Map<String, Object?>>> listSecrets() async {
    return db.query('bot_secret', orderBy: 'id ASC');
  }

  Future<String?> getSecretValue(String key) async {
    final rows = await db.query(
      'bot_secret',
      columns: ['secret_value'],
      where: 'secret_key = ?',
      whereArgs: [key],
      limit: 1,
    );
    return rows.isEmpty ? null : rows.first['secret_value'] as String?;
  }

  static List<String> get requiredSecretKeys =>
      _presetSecrets.map((secret) => secret['key']!).toList(growable: false);

  Future<List<String>> missingSecretKeys() async {
    if (!isInitialized) {
      await init();
    }
    final rows = await listSecrets();
    final values = <String, String?>{
      for (final row in rows)
        (row['secret_key'] as String? ?? ''): row['secret_value'] as String?,
    };
    return requiredSecretKeys
        .where((key) => (values[key] ?? '').trim().isEmpty)
        .toList();
  }

  Future<void> saveSecrets(Map<String, String?> values) async {
    if (values.isEmpty) {
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    final batch = db.batch();
    for (final entry in values.entries) {
      batch.update(
        'bot_secret',
        {'secret_value': entry.value, 'updated_at': now},
        where: 'secret_key = ?',
        whereArgs: [entry.key],
      );
    }
    await batch.commit(noResult: true);
  }

  Future<void> close() async {
    await _db?.close();
    _db = null;
    statusNotifier.value = DatabaseStatus.uninitialized;
    _log('数据库已关闭');
  }
}
