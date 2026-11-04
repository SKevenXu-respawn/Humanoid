import 'dart:io';

import 'package:csv/csv.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

Future<void> main(List<String> args) async {
  final appData = Platform.environment['APPDATA'];
  if (appData == null) {
    stderr.writeln('APPDATA not found');
    exit(1);
  }
  final dbPath = args.isNotEmpty
      ? args[0]
      : p.join(appData, 'com.humanoid', 'humanoid_flutter', 'humanoid.db');
  final dataDir = args.length > 1
      ? args[1]
      : r'D:\PROJECTS\serverProject\Humanoid\Humanoid-spring\docs\db_data';

  final dbFile = File(dbPath);
  if (!dbFile.existsSync()) {
    stderr.writeln('database not found: $dbPath');
    exit(1);
  }
  final dir = Directory(dataDir);
  if (!dir.existsSync()) {
    stderr.writeln('db_data not found: $dataDir');
    exit(1);
  }

  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;
  final db = await databaseFactory.openDatabase(dbPath);
  await db.execute('PRAGMA busy_timeout = 10000');

  try {
    await _ensureSchema(db);

    await _importTable(
      db,
      dir,
      'bot_user.csv',
      'bot_user',
      ['user_id', 'nickname', 'remark', 'enabled', 'created_at', 'updated_at'],
      (row) => {
        'user_id': _int(row, 1),
        'nickname': _text(row, 2),
        'remark': _text(row, 3),
        'enabled': _int(row, 4) ?? 1,
        'created_at': _date(row, 5),
        'updated_at': _date(row, 6),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_group.csv',
      'bot_group',
      ['group_id', 'name', 'remark', 'enabled', 'created_at', 'updated_at'],
      (row) => {
        'group_id': _int(row, 1),
        'name': _text(row, 2),
        'remark': _text(row, 3),
        'enabled': _int(row, 4) ?? 1,
        'created_at': _date(row, 5),
        'updated_at': _date(row, 6),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_conversation.csv',
      'bot_conversation',
      [
        'id',
        'user_id',
        'role',
        'message_type',
        'group_id',
        'onebot_message_id',
        'message_seq',
        'nickname',
        'segments',
        'content',
        'event_time',
        'created_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'role': _text(row, 2),
        'message_type': _text(row, 3),
        'group_id': _int(row, 4),
        'onebot_message_id': _int(row, 5),
        'message_seq': _int(row, 6),
        'nickname': _text(row, 7),
        'segments': _text(row, 9),
        'content': _text(row, 8),
        'event_time': _date(row, 10),
        'created_at': _date(row, 11),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_context.csv',
      'bot_context',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'role',
        'content',
        'image_refs',
        'created_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'role': _text(row, 4),
        'content': _text(row, 5),
        'image_refs': _text(row, 6),
        'created_at': _date(row, 7),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_summary.csv',
      'bot_summary',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'summary',
        'last_conversation_id',
        'created_at',
        'updated_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'summary': _text(row, 4),
        'last_conversation_id': _int(row, 5) ?? 0,
        'created_at': _date(row, 6),
        'updated_at': _date(row, 7),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_proactive_state.csv',
      'bot_proactive_state',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'last_checked_at',
        'last_proactive_at',
        'created_at',
        'updated_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'last_checked_at': _date(row, 5),
        'last_proactive_at': _date(row, 4),
        'created_at': _date(row, 6),
        'updated_at': _date(row, 7),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_reminder.csv',
      'bot_reminder',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'content',
        'remind_at',
        'status',
        'attempts',
        'created_at',
        'sent_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'content': _text(row, 4),
        'remind_at': _date(row, 5),
        'status': _text(row, 6),
        'attempts': _int(row, 7) ?? 0,
        'created_at': _date(row, 8),
        'sent_at': _date(row, 9),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_task.csv',
      'bot_task',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'title',
        'status',
        'pause_reason',
        'created_at',
        'updated_at',
        'completed_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'title': _text(row, 4),
        'status': _text(row, 5),
        'pause_reason': _text(row, 6),
        'created_at': _date(row, 7),
        'updated_at': _date(row, 8),
        'completed_at': _date(row, 9),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_task_step.csv',
      'bot_task_step',
      [
        'id',
        'task_id',
        'step_no',
        'content',
        'status',
        'attempts',
        'created_at',
        'updated_at',
        'completed_at',
      ],
      (row) {
        final created = _date(row, 6);
        final completed = _date(row, 7);
        return {
          'id': _int(row, 0),
          'task_id': _int(row, 1),
          'step_no': _int(row, 2),
          'content': _text(row, 3),
          'status': _text(row, 4),
          'attempts': _int(row, 5) ?? 0,
          'created_at': created,
          'updated_at': completed ?? created,
          'completed_at': completed,
        };
      },
    );
    await _importTable(
      db,
      dir,
      'bot_tool_call.csv',
      'bot_tool_call',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'tool_name',
        'arguments',
        'result',
        'is_error',
        'created_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'tool_name': _text(row, 4),
        'arguments': _text(row, 5),
        'result': _text(row, 6),
        'is_error': 0,
        'created_at': _date(row, 7),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_image.csv',
      'bot_image',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'conversation_id',
        'source',
        'data_url',
        'created_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'conversation_id': _int(row, 4),
        'source': _text(row, 5),
        'data_url': _text(row, 6),
        'created_at': _date(row, 7),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_friend_request.csv',
      'bot_friend_request',
      [
        'id',
        'user_id',
        'flag',
        'comment',
        'via',
        'source',
        'status',
        'remark',
        'event_time',
        'created_at',
        'handled_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'flag': _text(row, 2),
        'comment': _text(row, 3),
        'via': _text(row, 4),
        'source': _text(row, 5),
        'status': _text(row, 6),
        'remark': _text(row, 7),
        'event_time': _date(row, 8),
        'created_at': _date(row, 9),
        'handled_at': _date(row, 10),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_video_queue.csv',
      'bot_video_queue',
      [
        'id',
        'user_id',
        'message_type',
        'group_id',
        'url',
        'title',
        'comment',
        'source',
        'status',
        'attempts',
        'created_at',
        'sent_at',
        'sent_message_id',
      ],
      (row) => {
        'id': _int(row, 0),
        'user_id': _int(row, 1),
        'message_type': _text(row, 2),
        'group_id': _int(row, 3) ?? 0,
        'url': _text(row, 4),
        'title': _text(row, 5),
        'comment': _text(row, 6),
        'source': _text(row, 7),
        'status': _text(row, 8),
        'attempts': _int(row, 9) ?? 0,
        'created_at': _date(row, 10),
        'sent_at': _date(row, 11),
        'sent_message_id': _int(row, 12),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_memory.csv',
      'bot_memory',
      [
        'id',
        'memory_type',
        'scope_type',
        'scope_id',
        'owner_user_id',
        'content',
        'content_hash',
        'importance',
        'source',
        'source_conversation_id',
        'status',
        'created_at',
        'updated_at',
        'last_used_at',
      ],
      (row) => {
        'id': _int(row, 0),
        'memory_type': _text(row, 1),
        'scope_type': _text(row, 2),
        'scope_id': _int(row, 3) ?? 0,
        'owner_user_id': _int(row, 4) ?? 0,
        'content': _text(row, 5),
        'content_hash': _text(row, 6),
        'importance': _int(row, 7) ?? 3,
        'source': _text(row, 8),
        'source_conversation_id': _int(row, 9),
        'status': _text(row, 10),
        'created_at': _date(row, 11),
        'updated_at': _date(row, 12),
        'last_used_at': _date(row, 13),
      },
    );
    await _importTable(
      db,
      dir,
      'bot_memory_summary.csv',
      'bot_memory_summary',
      [
        'id',
        'scope_type',
        'scope_id',
        'owner_user_id',
        'summary',
        'memory_count',
        'created_at',
        'updated_at',
      ],
      (row) {
        final updated = _date(row, 6);
        return {
          'id': _int(row, 0),
          'scope_type': _text(row, 1),
          'scope_id': _int(row, 2) ?? 0,
          'owner_user_id': _int(row, 3) ?? 0,
          'summary': _text(row, 4),
          'memory_count': _int(row, 5) ?? 0,
          'created_at': updated,
          'updated_at': updated,
        };
      },
    );
    await _importSecrets(db, dir);
    await _importVoiceConfig(db, dir);

    print('\nMigration finished: $dbPath');
    print('Source: $dataDir');
  } finally {
    await db.close();
  }
}

Future<void> _ensureSchema(Database db) async {
  await db.execute('''
    CREATE TABLE IF NOT EXISTS bot_image (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      message_type TEXT NOT NULL,
      group_id INTEGER NOT NULL DEFAULT 0,
      conversation_id INTEGER,
      source TEXT,
      data_url TEXT NOT NULL,
      created_at INTEGER NOT NULL
    )
  ''');
  await db.execute('''
    CREATE TABLE IF NOT EXISTS bot_friend_request (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      flag TEXT NOT NULL UNIQUE,
      comment TEXT,
      via TEXT,
      source TEXT NOT NULL DEFAULT 'EVENT',
      status TEXT NOT NULL DEFAULT 'PENDING',
      remark TEXT,
      event_time INTEGER,
      created_at INTEGER NOT NULL,
      handled_at INTEGER
    )
  ''');
  await db.execute('''
    CREATE TABLE IF NOT EXISTS bot_voice_config (
      id INTEGER PRIMARY KEY,
      enabled INTEGER NOT NULL DEFAULT 1,
      speaker TEXT NOT NULL,
      updated_at INTEGER NOT NULL
    )
  ''');

  await _ensureColumn(db, 'bot_user', 'remark', 'TEXT');
  await _ensureColumn(db, 'bot_user', 'updated_at', 'INTEGER');
  await _ensureColumn(db, 'bot_group', 'remark', 'TEXT');
  await _ensureColumn(db, 'bot_group', 'updated_at', 'INTEGER');
  await _ensureColumn(db, 'bot_task_step', 'completed_at', 'INTEGER');
  await _ensureColumn(db, 'bot_proactive_state', 'updated_at', 'INTEGER');
  await _ensureColumn(
    db,
    'bot_video_queue',
    'source',
    "TEXT NOT NULL DEFAULT 'PROACTIVE'",
  );
  await _ensureColumn(
    db,
    'bot_video_queue',
    'attempts',
    'INTEGER NOT NULL DEFAULT 0',
  );
  await _ensureColumn(db, 'bot_video_queue', 'sent_message_id', 'INTEGER');
  await _ensureColumn(db, 'bot_memory', 'source_conversation_id', 'INTEGER');
  await _ensureColumn(db, 'bot_memory', 'last_used_at', 'INTEGER');
  await _ensureColumn(
    db,
    'bot_memory_summary',
    'memory_count',
    'INTEGER NOT NULL DEFAULT 0',
  );
}

Future<void> _ensureColumn(
  Database db,
  String table,
  String column,
  String definition,
) async {
  final info = await db.rawQuery('PRAGMA table_info("$table")');
  if (info.isEmpty) {
    return;
  }
  if (info.any((row) => row['name'] == column)) {
    return;
  }
  await db.execute('ALTER TABLE "$table" ADD COLUMN $column $definition');
}

Future<void> _importTable(
  Database db,
  Directory dir,
  String fileName,
  String table,
  List<String> columns,
  Map<String, Object?> Function(List<dynamic> row) mapper,
) async {
  final file = File(p.join(dir.path, fileName));
  if (!file.existsSync()) {
    print('$table: missing $fileName');
    return;
  }
  final rows = _readCsv(file);
  if (rows.isEmpty) {
    print('$table: 0 rows');
    return;
  }

  final sql = 'INSERT OR REPLACE INTO "$table" '
      '(${columns.map((c) => '"$c"').join(',')}) '
      'VALUES (${List.filled(columns.length, '?').join(',')})';
  var imported = 0;
  await db.transaction((txn) async {
    for (final row in rows) {
      final values = mapper(row);
      if (values.values.every((value) => value == null)) {
        continue;
      }
      await txn.rawInsert(sql, columns.map((c) => values[c]).toList());
      imported++;
    }
  });
  print('$table: $imported rows');
}

Future<void> _importSecrets(Database db, Directory dir) async {
  final file = File(p.join(dir.path, 'bot_secret.csv'));
  if (!file.existsSync()) {
    return;
  }
  final rows = _readCsv(file);
  const mapping = {
    'deepseek_api_key': (
      key: 'deepseek_key',
      name: 'DeepSeek Key',
      url: 'https://platform.deepseek.com/api_keys',
    ),
    'llbot_api_token': (
      key: 'llonebot_key',
      name: 'LLOneBot Key',
      url: 'https://auth.luckylillia.com/tokens',
    ),
    'voice_api_key': (
      key: 'speech_key',
      name: '语音服务 Key',
      url:
          'https://console.volcengine.com/ark/region:cn-beijing/model/detail?name=doubao-seed-tts-2-0',
    ),
    'brave_api_key': (
      key: 'brave_key',
      name: 'Brave Search Key',
      url: 'https://api.search.brave.com/app/keys',
    ),
  };
  var imported = 0;
  await db.transaction((txn) async {
    for (final row in rows) {
      final sourceKey = _text(row, 1);
      final target = mapping[sourceKey];
      if (target == null) {
        continue;
      }
      final value = _text(row, 2);
      final updatedAt = _date(row, 3);
      final existing = await txn.query(
        'bot_secret',
        columns: ['id'],
        where: 'secret_key = ?',
        whereArgs: [target.key],
        limit: 1,
      );
      if (existing.isEmpty) {
        await txn.insert('bot_secret', {
          'secret_key': target.key,
          'secret_name': target.name,
          'secret_value': value,
          'secret_url': target.url,
          'updated_at': updatedAt,
        });
      } else {
        await txn.update(
          'bot_secret',
          {
            'secret_value': value,
            'updated_at': updatedAt,
          },
          where: 'secret_key = ?',
          whereArgs: [target.key],
        );
      }
      imported++;
    }
  });
  print('bot_secret: $imported rows');
}

Future<void> _importVoiceConfig(Database db, Directory dir) async {
  final file = File(p.join(dir.path, 'bot_voice_config.csv'));
  if (!file.existsSync()) {
    return;
  }
  final rows = _readCsv(file);
  if (rows.isEmpty) {
    print('bot_voice_config: 0 rows');
    return;
  }
  final now = DateTime.now().millisecondsSinceEpoch;
  await db.transaction((txn) async {
    for (final row in rows) {
      final enabled = _int(row, 1) ?? 1;
      final speaker = _text(row, 2) ?? '';
      final updated = _date(row, 3) ?? now;
      await txn.insert(
        'bot_voice_config',
        {
          'id': _int(row, 0) ?? 1,
          'enabled': enabled,
          'speaker': speaker,
          'updated_at': updated,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
      await txn.insert(
        'app_setting',
        {
          'key': 'voice_enabled',
          'value': enabled == 1 ? '1' : '0',
          'updated_at': updated,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
      await txn.insert(
        'app_setting',
        {
          'key': 'voice_speaker',
          'value': speaker,
          'updated_at': updated,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    }
  });
  print('bot_voice_config: ${rows.length} rows');
}

List<List<dynamic>> _readCsv(File file) {
  final content = file.readAsStringSync();
  return Csv(
    autoDetect: true,
    skipEmptyLines: true,
    dynamicTyping: false,
  ).decode(content);
}

String? _text(List<dynamic> row, int index) {
  if (index >= row.length) {
    return null;
  }
  final value = row[index]?.toString() ?? '';
  final trimmed = value.trim();
  if (trimmed.isEmpty || trimmed == 'NULL' || trimmed == r'\N') {
    return null;
  }
  return value;
}

int? _int(List<dynamic> row, int index) {
  final value = _text(row, index);
  if (value == null) {
    return null;
  }
  return int.tryParse(value.trim());
}

int? _date(List<dynamic> row, int index) {
  final value = _text(row, index);
  if (value == null) {
    return null;
  }
  final normalized = value.trim().replaceFirst(' ', 'T');
  final hasZone = RegExp(r'(Z|[+-]\d{2}:?\d{2})$').hasMatch(normalized);
  final withZone = hasZone ? normalized : '${normalized}+08:00';
  return DateTime.tryParse(withZone)?.millisecondsSinceEpoch;
}
