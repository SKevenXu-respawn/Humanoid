import 'package:csv/csv.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../database_util.dart';

class SqliteColumn {
  final String name;
  final String type;
  final bool notNull;
  final Object? defaultValue;
  final int primaryKeyOrder;

  const SqliteColumn({
    required this.name,
    required this.type,
    required this.notNull,
    required this.defaultValue,
    required this.primaryKeyOrder,
  });

  bool get isPrimaryKey => primaryKeyOrder > 0;

  bool get isAutoInteger =>
      isPrimaryKey && type.toUpperCase().contains('INT');
}

class SqliteRowPage {
  final List<SqliteColumn> columns;
  final List<Map<String, Object?>> rows;
  final int total;
  final int limit;
  final int offset;

  const SqliteRowPage({
    required this.columns,
    required this.rows,
    required this.total,
    required this.limit,
    required this.offset,
  });
}

class SqliteRowKey {
  final Map<String, Object?> values;
  final bool rowid;

  const SqliteRowKey(this.values, {required this.rowid});

  static SqliteRowKey fromRow(
    List<SqliteColumn> columns,
    Map<String, Object?> row,
  ) {
    final primaryKeys = columns.where((column) => column.isPrimaryKey);
    if (primaryKeys.isEmpty) {
      return SqliteRowKey(
        {'__humanoid_rowid__': row['__humanoid_rowid__']},
        rowid: true,
      );
    }
    return SqliteRowKey(
      {for (final column in primaryKeys) column.name: row[column.name]},
      rowid: false,
    );
  }

  String get whereClause => values.keys
      .map((name) => '${SqliteAdminService.quoteIdentifier(name)} = ?')
      .join(' AND ');

  List<Object?> get whereArgs => values.values.toList();
}

class CsvImportOptions {
  final bool hasHeader;
  final bool emptyAsNull;
  final bool replaceOnConflict;

  const CsvImportOptions({
    this.hasHeader = true,
    this.emptyAsNull = true,
    this.replaceOnConflict = true,
  });
}

class CsvImportResult {
  final int imported;
  final int skipped;
  final List<String> errors;
  final List<String> warnings;

  const CsvImportResult({
    required this.imported,
    required this.skipped,
    required this.errors,
    required this.warnings,
  });
}

class SqliteAdminService {
  Database get _db => DatabaseUtil.instance.db;

  static String quoteIdentifier(String name) =>
      '"${name.replaceAll('"', '""')}"';

  Future<List<String>> listTables() async {
    final rows = await _db.rawQuery(
      "SELECT name FROM sqlite_master "
      "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    );
    return rows
        .map((row) => row['name']?.toString())
        .whereType<String>()
        .toList(growable: false);
  }

  Future<List<SqliteColumn>> tableColumns(String table) async {
    final rows = await _db.rawQuery(
      'PRAGMA table_info(${quoteIdentifier(table)})',
    );
    return rows
        .map(
          (row) => SqliteColumn(
            name: row['name']?.toString() ?? '',
            type: row['type']?.toString() ?? '',
            notNull: (row['notnull'] as int? ?? 0) != 0,
            defaultValue: row['dflt_value'],
            primaryKeyOrder: row['pk'] as int? ?? 0,
          ),
        )
        .toList(growable: false);
  }

  Future<int> countRows(String table) async {
    final rows = await _db.rawQuery(
      'SELECT COUNT(*) AS c FROM ${quoteIdentifier(table)}',
    );
    return rows.first['c'] as int? ?? 0;
  }

  Future<SqliteRowPage> fetchRows(
    String table, {
    int limit = 50,
    int offset = 0,
  }) async {
    final columns = await tableColumns(table);
    final primaryKeys = columns.where((column) => column.isPrimaryKey);
    final selection = primaryKeys.isEmpty
        ? '*, rowid AS __humanoid_rowid__'
        : '*';
    final rows = await _db.rawQuery(
      'SELECT $selection FROM ${quoteIdentifier(table)} LIMIT ? OFFSET ?',
      [limit, offset],
    );
    final total = await countRows(table);
    return SqliteRowPage(
      columns: columns,
      rows: rows,
      total: total,
      limit: limit,
      offset: offset,
    );
  }

  Future<void> insertRow(String table, Map<String, Object?> values) async {
    await _db.insert(table, values);
  }

  Future<void> updateRow(
    String table,
    Map<String, Object?> values,
    SqliteRowKey key,
  ) async {
    if (values.isEmpty) {
      return;
    }
    await _db.update(
      table,
      values,
      where: key.whereClause,
      whereArgs: key.whereArgs,
    );
  }

  Future<void> deleteRow(String table, SqliteRowKey key) async {
    await _db.delete(
      table,
      where: key.whereClause,
      whereArgs: key.whereArgs,
    );
  }

  Future<CsvImportResult> importCsv({
    required String table,
    required String content,
    CsvImportOptions options = const CsvImportOptions(),
  }) async {
    final cleaned = content.replaceFirst(String.fromCharCode(0xFEFF), '');
    final rows = Csv(
      autoDetect: true,
      skipEmptyLines: true,
      dynamicTyping: false,
    ).decode(cleaned);
    if (rows.isEmpty) {
      return const CsvImportResult(
        imported: 0,
        skipped: 0,
        errors: [],
        warnings: ['CSV 为空'],
      );
    }

    final columns = await tableColumns(table);
    if (columns.isEmpty) {
      throw StateError('表 $table 不存在或没有列');
    }
    final columnByName = {
      for (final column in columns) column.name.toLowerCase(): column,
    };

    final headers = options.hasHeader
        ? rows.first.map((value) => value?.toString().trim() ?? '').toList()
        : columns.map((column) => column.name).toList();
    final dataStart = options.hasHeader ? 1 : 0;
    final mapping = <int, SqliteColumn>{};
    final unknownHeaders = <String>[];

    for (var i = 0; i < headers.length; i++) {
      final column = columnByName[headers[i].toLowerCase()];
      if (column != null) {
        mapping[i] = column;
      } else if (headers[i].isNotEmpty) {
        unknownHeaders.add(headers[i]);
      }
    }
    if (mapping.isEmpty) {
      throw FormatException('CSV 列名与表 $table 不匹配');
    }

    var imported = 0;
    var skipped = 0;
    final errors = <String>[];
    final warnings = <String>[
      if (unknownHeaders.isNotEmpty) '忽略未知列: ${unknownHeaders.join(', ')}',
    ];

    await _db.transaction((transaction) async {
      for (var rowIndex = dataStart; rowIndex < rows.length; rowIndex++) {
        final row = rows[rowIndex];
        if (row.every((value) => (value?.toString() ?? '').trim().isEmpty)) {
          skipped++;
          continue;
        }

        final values = <String, Object?>{};
        try {
          for (final entry in mapping.entries) {
            if (entry.key >= row.length) {
              continue;
            }
            final column = entry.value;
            final raw = row[entry.key]?.toString() ?? '';
            final value = _convertCsvValue(
              raw,
              column,
              emptyAsNull: options.emptyAsNull,
            );
            if (column.isAutoInteger &&
                (value == null || raw.trim().isEmpty)) {
              continue;
            }
            values[column.name] = value;
          }
          if (values.isEmpty) {
            skipped++;
            continue;
          }
          await transaction.insert(
            table,
            values,
            conflictAlgorithm: options.replaceOnConflict
                ? ConflictAlgorithm.replace
                : ConflictAlgorithm.abort,
          );
          imported++;
        } catch (e) {
          skipped++;
          if (errors.length < 20) {
            errors.add('第 ${rowIndex + 1} 行: $e');
          }
        }
      }
    });

    return CsvImportResult(
      imported: imported,
      skipped: skipped,
      errors: errors,
      warnings: warnings,
    );
  }

  static Object? _convertCsvValue(
    String raw,
    SqliteColumn column, {
    required bool emptyAsNull,
  }) {
    final text = raw.trim();
    if (text.isEmpty) {
      return emptyAsNull ? null : raw;
    }
    final upper = text.toUpperCase();
    if (upper == 'NULL' || text == r'\N') {
      return null;
    }

    final type = column.type.toUpperCase();
    if (type.contains('INT')) {
      final value = int.tryParse(text);
      if (value == null) {
        throw FormatException('${column.name} 需要整数: $text');
      }
      return value;
    }
    if (type.contains('REAL') ||
        type.contains('FLOA') ||
        type.contains('DOUB') ||
        type.contains('NUM') ||
        type.contains('DEC')) {
      final value = double.tryParse(text);
      if (value == null) {
        throw FormatException('${column.name} 需要数字: $text');
      }
      return value;
    }
    return raw;
  }
}
