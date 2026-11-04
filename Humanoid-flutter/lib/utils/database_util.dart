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
  static const _dbVersion = 4;

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
    {
      'key': 'brave_key',
      'name': 'Brave Search Key',
      'url': 'https://api.search.brave.com/app/keys',
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
    await _createBotTables(database);
    await _seedSecrets(database);
  }

  Future<void> _onUpgrade(Database database, int oldVersion, int newVersion) async {
    if (oldVersion < 2) {
      await _createBotSecretTable(database);
    }
    if (oldVersion < 3) {
      await _ensureSecretUrlColumn(database);
    }
    if (oldVersion < 4) {
      await _createBotTables(database);
    }
  }

  Future<void> _ensureSchema(Database database) async {
    await _createBotSecretTable(database);
    await _ensureSecretUrlColumn(database);
    await _createBotTables(database);
    await _ensureBotColumns(database);
    await _seedSecrets(database);
  }

  Future<void> _ensureBotColumns(Database database) async {
    await _ensureColumn(database, 'bot_video_queue', 'source', 'TEXT');
    await _ensureColumn(
      database,
      'bot_video_queue',
      'attempts',
      'INTEGER NOT NULL DEFAULT 0',
    );
    await _ensureColumn(
      database,
      'bot_video_queue',
      'sent_message_id',
      'INTEGER',
    );
    await _ensureColumn(
      database,
      'bot_task_step',
      'completed_at',
      'INTEGER',
    );
    await _ensureColumn(
      database,
      'bot_memory',
      'source_conversation_id',
      'INTEGER',
    );
    await _ensureColumn(database, 'bot_memory', 'last_used_at', 'INTEGER');
    await _ensureColumn(
      database,
      'bot_memory_summary',
      'memory_count',
      'INTEGER',
    );
  }

  Future<void> _ensureColumn(
    Database database,
    String table,
    String column,
    String definition,
  ) async {
    final columns = await database.rawQuery('PRAGMA table_info($table)');
    if (columns.isEmpty) {
      return;
    }
    if (columns.any((row) => row['name'] == column)) {
      return;
    }
    await database.execute(
      'ALTER TABLE $table ADD COLUMN $column $definition',
    );
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

  Future<void> _createBotTables(Database database) async {
    const statements = <String>[
      '''
      CREATE TABLE IF NOT EXISTS bot_user (
        user_id INTEGER PRIMARY KEY,
        nickname TEXT,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_group (
        group_id INTEGER PRIMARY KEY,
        name TEXT,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_image (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL,
        group_id INTEGER NOT NULL DEFAULT 0,
        conversation_id INTEGER,
        source TEXT,
        data_url TEXT,
        created_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_friend_request (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        flag TEXT NOT NULL,
        comment TEXT,
        via TEXT,
        source TEXT NOT NULL DEFAULT 'EVENT',
        status TEXT NOT NULL DEFAULT 'PENDING',
        remark TEXT,
        event_time INTEGER,
        created_at INTEGER NOT NULL,
        handled_at INTEGER,
        UNIQUE(flag)
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_voice_config (
        id INTEGER PRIMARY KEY,
        enabled INTEGER NOT NULL DEFAULT 1,
        speaker TEXT,
        updated_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_prompt (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        prompt_key TEXT NOT NULL UNIQUE,
        content TEXT,
        updated_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_user_prompt (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL UNIQUE,
        content TEXT,
        updated_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_context (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL,
        group_id INTEGER NOT NULL DEFAULT 0,
        role TEXT NOT NULL,
        content TEXT,
        image_refs TEXT,
        created_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE INDEX IF NOT EXISTS idx_bot_context_scope
      ON bot_context(user_id, message_type, group_id, id)
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_conversation (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        role TEXT NOT NULL,
        message_type TEXT NOT NULL,
        group_id INTEGER,
        onebot_message_id INTEGER,
        message_seq INTEGER,
        nickname TEXT,
        segments TEXT,
        content TEXT,
        event_time INTEGER,
        created_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_tool_call (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER,
        message_type TEXT,
        group_id INTEGER,
        tool_name TEXT NOT NULL,
        arguments TEXT,
        result TEXT,
        is_error INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_summary (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL,
        group_id INTEGER NOT NULL DEFAULT 0,
        summary TEXT,
        last_conversation_id INTEGER,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        UNIQUE(user_id, message_type, group_id)
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_memory (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        memory_type TEXT NOT NULL,
        scope_type TEXT NOT NULL,
        scope_id INTEGER NOT NULL,
        owner_user_id INTEGER NOT NULL,
        content TEXT NOT NULL,
        content_hash TEXT,
        importance INTEGER NOT NULL DEFAULT 3,
        source TEXT NOT NULL DEFAULT 'AI',
        status TEXT NOT NULL DEFAULT 'ACTIVE',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_memory_participant (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        memory_id INTEGER NOT NULL,
        user_id INTEGER NOT NULL,
        UNIQUE(memory_id, user_id)
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_memory_summary (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        scope_type TEXT NOT NULL,
        scope_id INTEGER NOT NULL,
        owner_user_id INTEGER NOT NULL,
        summary TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        UNIQUE(scope_type, scope_id, owner_user_id)
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_task (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL,
        group_id INTEGER,
        title TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',
        pause_reason TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        completed_at INTEGER
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_task_step (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        task_id INTEGER NOT NULL,
        step_no INTEGER NOT NULL,
        content TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',
        attempts INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_reminder (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL,
        group_id INTEGER,
        content TEXT NOT NULL,
        remind_at INTEGER NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',
        attempts INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        sent_at INTEGER
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_video_queue (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL DEFAULT 'private',
        group_id INTEGER NOT NULL DEFAULT 0,
        url TEXT NOT NULL,
        title TEXT,
        comment TEXT,
        source TEXT,
        status TEXT NOT NULL DEFAULT 'PENDING',
        attempts INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        sent_at INTEGER,
        sent_message_id INTEGER
      )
      ''',
      '''
      CREATE TABLE IF NOT EXISTS bot_proactive_state (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL,
        message_type TEXT NOT NULL DEFAULT 'private',
        group_id INTEGER NOT NULL DEFAULT 0,
        last_checked_at INTEGER,
        last_proactive_at INTEGER,
        created_at INTEGER NOT NULL,
        UNIQUE(user_id, message_type, group_id)
      )
      ''',
    ];

    for (final statement in statements) {
      await database.execute(statement);
    }
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

  static List<String> get requiredSecretKeys => _presetSecrets
      .map((secret) => secret['key']!)
      .where((key) => key != 'brave_key')
      .toList(growable: false);

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
