import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:humanoid_flutter/utils/database/sqlite_admin_service.dart';
import 'package:humanoid_flutter/utils/database_util.dart';

class DatabasePage extends StatefulWidget {
  const DatabasePage({super.key});

  @override
  State<DatabasePage> createState() => _DatabasePageState();
}

class _DatabasePageState extends State<DatabasePage> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);

  final SqliteAdminService _service = SqliteAdminService();

  List<String> _tables = [];
  String? _selectedTable;
  SqliteRowPage? _pageData;
  int _pageIndex = 0;
  int _pageSize = 50;
  bool _loading = true;
  bool _importing = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadTables();
  }

  Future<void> _loadTables() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (!DatabaseUtil.instance.isInitialized) {
        await DatabaseUtil.instance.init();
      }
      final tables = await _service.listTables();
      var selected = _selectedTable;
      if (selected == null || !tables.contains(selected)) {
        selected = tables.isEmpty ? null : tables.first;
      }
      if (!mounted) {
        return;
      }
      setState(() {
        _tables = tables;
        _selectedTable = selected;
        _pageIndex = 0;
      });
      if (selected == null) {
        setState(() {
          _pageData = null;
          _loading = false;
        });
      } else {
        await _loadRows();
      }
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  Future<void> _loadRows() async {
    final table = _selectedTable;
    if (table == null) {
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final data = await _service.fetchRows(
        table,
        limit: _pageSize,
        offset: _pageIndex * _pageSize,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _pageData = data;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  Future<void> _selectTable(String? table) async {
    if (table == null || table == _selectedTable) {
      return;
    }
    setState(() {
      _selectedTable = table;
      _pageIndex = 0;
    });
    await _loadRows();
  }

  Future<void> _showAddRow() async {
    final table = _selectedTable;
    if (table == null) {
      return;
    }
    final columns = await _service.tableColumns(table);
    if (!mounted) {
      return;
    }
    final changed = await showDialog<bool>(
      context: context,
      builder: (context) => _RowEditorDialog(
        service: _service,
        table: table,
        columns: columns,
      ),
    );
    if (changed == true) {
      await _loadRows();
    }
  }

  Future<void> _showEditRow(Map<String, Object?> row) async {
    final table = _selectedTable;
    final data = _pageData;
    if (table == null || data == null) {
      return;
    }
    final changed = await showDialog<bool>(
      context: context,
      builder: (context) => _RowEditorDialog(
        service: _service,
        table: table,
        columns: data.columns,
        row: row,
      ),
    );
    if (changed == true) {
      await _loadRows();
    }
  }

  Future<void> _deleteRow(Map<String, Object?> row) async {
    final table = _selectedTable;
    final data = _pageData;
    if (table == null || data == null) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('删除这一行？'),
        content: const Text('删除后无法恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            style: FilledButton.styleFrom(backgroundColor: _danger),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) {
      return;
    }
    try {
      await _service.deleteRow(
        table,
        SqliteRowKey.fromRow(data.columns, row),
      );
      await _loadRows();
      if (mounted) {
        _showSnack('已删除');
      }
    } catch (e) {
      if (mounted) {
        _showSnack('删除失败: $e');
      }
    }
  }

  Future<void> _pickCsv() async {
    final table = _selectedTable;
    if (table == null || _importing) {
      return;
    }
    try {
      final file = await FilePicker.pickFile(
        dialogTitle: '选择 MySQL CSV 导出文件',
        type: FileType.custom,
        allowedExtensions: ['csv', 'txt', 'tsv'],
      );
      if (file == null) {
        return;
      }
      final path = file.path;
      if (path == null || path.isEmpty) {
        _showSnack('无法读取文件路径');
        return;
      }
      final bytes = await File(path).readAsBytes();
      final content = utf8
          .decode(bytes, allowMalformed: true)
          .replaceFirst(String.fromCharCode(0xFEFF), '');
      if (!mounted) {
        return;
      }
      final options = await showDialog<CsvImportOptions>(
        context: context,
        builder: (context) => _CsvImportOptionsDialog(
          fileName: file.name,
          table: table,
        ),
      );
      if (options == null) {
        return;
      }
      await _importCsv(table, content, options);
    } catch (e) {
      if (mounted) {
        _showSnack('读取 CSV 失败: $e');
      }
    }
  }

  Future<void> _importCsv(
    String table,
    String content,
    CsvImportOptions options,
  ) async {
    if (!mounted) {
      return;
    }
    setState(() => _importing = true);
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (context) => const Dialog(
        backgroundColor: Colors.transparent,
        child: _ImportProgressDialog(),
      ),
    );

    try {
      final result = await _service.importCsv(
        table: table,
        content: content,
        options: options,
      );
      if (!mounted) {
        return;
      }
      final navigator = Navigator.of(context, rootNavigator: true);
      if (navigator.canPop()) {
        navigator.pop();
      }
      await _loadRows();
      if (!mounted) {
        return;
      }
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('导入完成'),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('成功导入：${result.imported} 行'),
                Text('跳过：${result.skipped} 行'),
                if (result.warnings.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  for (final warning in result.warnings)
                    Text(
                      warning,
                      style: const TextStyle(color: Colors.orange),
                    ),
                ],
                if (result.errors.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  for (final error in result.errors)
                    Text(error, style: const TextStyle(color: _danger)),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('知道了'),
            ),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) {
        return;
      }
      final navigator = Navigator.of(context, rootNavigator: true);
      if (navigator.canPop()) {
        navigator.pop();
      }
      _showSnack('导入失败: $e');
    } finally {
      if (mounted) {
        setState(() => _importing = false);
      }
    }
  }

  void _showSnack(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            _buildToolbar(),
            const SizedBox(height: 16),
            Expanded(child: _buildDataArea()),
          ],
        ),
      ),
    );
  }

  Widget _buildToolbar() {
    return _glass(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Wrap(
        spacing: 12,
        runSpacing: 12,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          SizedBox(
            width: 250,
            child: DropdownButtonFormField<String>(
              key: ValueKey(_selectedTable ?? ''),
              initialValue:
                  _tables.contains(_selectedTable) ? _selectedTable : null,
              isDense: true,
              isExpanded: true,
              decoration: InputDecoration(
                labelText: '数据表',
                labelStyle: TextStyle(
                  fontSize: 12,
                  color: Colors.black.withValues(alpha: 0.55),
                ),
                isDense: true,
                filled: true,
                fillColor: Colors.white.withValues(alpha: 0.65),
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: BorderSide(
                    color: Colors.white.withValues(alpha: 0.8),
                  ),
                ),
              ),
              style: const TextStyle(fontSize: 13, color: Color(0xDD000000)),
              dropdownColor: Colors.white,
              items: [
                for (final table in _tables)
                  DropdownMenuItem(
                    value: table,
                    child: Text(
                      table,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
              ],
              onChanged: _loading ? null : _selectTable,
            ),
          ),
          OutlinedButton.icon(
            onPressed: _loading ? null : _loadTables,
            icon: const Icon(Icons.refresh_rounded, size: 18),
            label: const Text('刷新'),
            style: _outlineStyle(_accent),
          ),
          FilledButton.icon(
            onPressed: _selectedTable == null ? null : _showAddRow,
            icon: const Icon(Icons.add_rounded, size: 18),
            label: const Text('新增行'),
            style: FilledButton.styleFrom(
              backgroundColor: _accent,
              foregroundColor: Colors.white,
              minimumSize: const Size(0, 40),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
          ),
          OutlinedButton.icon(
            onPressed: _selectedTable == null || _importing ? null : _pickCsv,
            icon: const Icon(Icons.upload_file_rounded, size: 18),
            label: const Text('导入 CSV'),
            style: _outlineStyle(_accent),
          ),
          Text(
            '共 ${_pageData?.total ?? 0} 行',
            style: TextStyle(
              fontSize: 12,
              color: Colors.black.withValues(alpha: 0.55),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDataArea() {
    if (_loading) {
      return _glass(
        child: const Center(child: CircularProgressIndicator(strokeWidth: 2.4)),
      );
    }
    if (_error != null) {
      return _glass(
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '读取失败: $_error',
                maxLines: 4,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 13, color: _danger),
              ),
              const SizedBox(height: 12),
              TextButton(onPressed: _loadTables, child: const Text('重试')),
            ],
          ),
        ),
      );
    }
    final data = _pageData;
    if (data == null || _selectedTable == null) {
      return _glass(
        child: const Center(
          child: Text(
            '请选择数据表',
            style: TextStyle(fontSize: 13, color: Colors.black45),
          ),
        ),
      );
    }
    if (data.rows.isEmpty) {
      return _glass(
        child: const Center(
          child: Text(
            '暂无数据',
            style: TextStyle(fontSize: 13, color: Colors.black45),
          ),
        ),
      );
    }

    return _glass(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.vertical,
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minWidth: MediaQuery.sizeOf(context).width * 0.55,
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: DataTable(
                      showCheckboxColumn: false,
                      headingRowHeight: 42,
                      dataRowMinHeight: 42,
                      dataRowMaxHeight: 52,
                      horizontalMargin: 12,
                      columnSpacing: 22,
                      columns: [
                        for (final column in data.columns
                            .where((c) => c.name != '__humanoid_rowid__'))
                          DataColumn(
                            label: Text(
                              column.name,
                              style: const TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                color: Color(0xDD000000),
                              ),
                            ),
                          ),
                        const DataColumn(
                          label: Text(
                            '操作',
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: Color(0xDD000000),
                            ),
                          ),
                        ),
                      ],
                      rows: [
                        for (final row in data.rows)
                          DataRow(
                            cells: [
                              for (final column in data.columns
                                  .where((c) => c.name != '__humanoid_rowid__'))
                                DataCell(_cellValue(row[column.name])),
                              DataCell(
                                Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    _actionIcon(
                                      Icons.edit_rounded,
                                      Colors.black54,
                                      '编辑',
                                      () => _showEditRow(row),
                                    ),
                                    _actionIcon(
                                      Icons.delete_outline_rounded,
                                      _danger,
                                      '删除',
                                      () => _deleteRow(row),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
          _buildPagination(data),
        ],
      ),
    );
  }

  Widget _cellValue(Object? value) {
    final full = value == null
        ? 'NULL'
        : value is List<int>
            ? '<BLOB ${value.length} B>'
            : value.toString().replaceAll(RegExp(r'\s+'), ' ');
    final short =
        full.length > 120 ? '${full.substring(0, 120)}…' : full;
    return Tooltip(
      message: full,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 220),
        child: Text(
          short,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            color: value == null
                ? Colors.black38
                : const Color(0xDD000000),
          ),
        ),
      ),
    );
  }

  Widget _actionIcon(
    IconData icon,
    Color color,
    String tooltip,
    VoidCallback onPressed,
  ) {
    return IconButton(
      onPressed: onPressed,
      icon: Icon(icon, size: 16),
      color: color,
      tooltip: tooltip,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints.tightFor(width: 30, height: 30),
    );
  }

  Widget _buildPagination(SqliteRowPage data) {
    final totalPages = math.max(1, (data.total + _pageSize - 1) ~/ _pageSize);
    final current = _pageIndex + 1;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.35),
        border: Border(
          top: BorderSide(color: Colors.white.withValues(alpha: 0.55)),
        ),
      ),
      child: Row(
        children: [
          Text(
            '第 $current / $totalPages 页',
            style: TextStyle(
              fontSize: 12,
              color: Colors.black.withValues(alpha: 0.55),
            ),
          ),
          const Spacer(),
          DropdownButton<int>(
            value: _pageSize,
            underline: const SizedBox.shrink(),
            style: const TextStyle(fontSize: 12, color: Color(0xDD000000)),
            items: const [
              DropdownMenuItem(value: 20, child: Text('20 行/页')),
              DropdownMenuItem(value: 50, child: Text('50 行/页')),
              DropdownMenuItem(value: 100, child: Text('100 行/页')),
            ],
            onChanged: _loading
                ? null
                : (value) {
                    if (value == null) {
                      return;
                    }
                    setState(() {
                      _pageSize = value;
                      _pageIndex = 0;
                    });
                    _loadRows();
                  },
          ),
          const SizedBox(width: 8),
          IconButton(
            onPressed:
                _pageIndex > 0 ? () {
                  setState(() => _pageIndex--);
                  _loadRows();
                } : null,
            icon: const Icon(Icons.chevron_left_rounded),
            tooltip: '上一页',
          ),
          IconButton(
            onPressed: _pageIndex + 1 < totalPages
                ? () {
                    setState(() => _pageIndex++);
                    _loadRows();
                  }
                : null,
            icon: const Icon(Icons.chevron_right_rounded),
            tooltip: '下一页',
          ),
        ],
      ),
    );
  }

  Widget _glass({
    required Widget child,
    EdgeInsetsGeometry padding = const EdgeInsets.all(16),
  }) {
    return Container(
      padding: padding,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Colors.white.withValues(alpha: 0.86),
            Colors.white.withValues(alpha: 0.64),
          ],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withValues(alpha: 0.70)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 24,
            offset: const Offset(0, 12),
          ),
          BoxShadow(
            color: _accent.withValues(alpha: 0.14),
            blurRadius: 30,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: child,
    );
  }

  ButtonStyle _outlineStyle(Color color) {
    return OutlinedButton.styleFrom(
      foregroundColor: color,
      backgroundColor: Colors.white.withValues(alpha: 0.55),
      side: BorderSide(color: color.withValues(alpha: 0.35)),
      minimumSize: const Size(0, 40),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    );
  }
}

class _RowEditorDialog extends StatefulWidget {
  final SqliteAdminService service;
  final String table;
  final List<SqliteColumn> columns;
  final Map<String, Object?>? row;

  const _RowEditorDialog({
    required this.service,
    required this.table,
    required this.columns,
    this.row,
  });

  @override
  State<_RowEditorDialog> createState() => _RowEditorDialogState();
}

class _RowEditorDialogState extends State<_RowEditorDialog> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);

  final Map<String, TextEditingController> _controllers = {};
  bool _saving = false;
  String? _error;

  bool get _isEditing => widget.row != null;

  List<SqliteColumn> get _displayColumns => widget.columns;

  @override
  void initState() {
    super.initState();
    for (final column in _displayColumns) {
      final value = widget.row?[column.name];
      _controllers[column.name] =
          TextEditingController(text: value == null ? '' : '$value');
    }
  }

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving) {
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });

    try {
      final values = <String, Object?>{};
      for (final column in _displayColumns) {
        if (_isEditing && column.isPrimaryKey) {
          continue;
        }
        if (column.type.toUpperCase().contains('BLOB')) {
          continue;
        }
        final controller = _controllers[column.name]!;
        final raw = controller.text;
        if (raw.trim().isEmpty) {
          if (column.isAutoInteger) {
            continue;
          }
          if (column.notNull && column.defaultValue == null) {
            throw FormatException('${column.name} 不能为空');
          }
          if (!_isEditing && column.defaultValue != null) {
            continue;
          }
          values[column.name] = null;
          continue;
        }
        values[column.name] = _convert(raw, column);
      }

      if (_isEditing) {
        final key = SqliteRowKey.fromRow(widget.columns, widget.row!);
        await widget.service.updateRow(widget.table, values, key);
      } else {
        await widget.service.insertRow(widget.table, values);
      }
      if (!mounted) {
        return;
      }
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = '$e';
        _saving = false;
      });
    }
  }

  Object? _convert(String raw, SqliteColumn column) {
    final type = column.type.toUpperCase();
    if (type.contains('BLOB')) {
      throw FormatException('${column.name} 是 BLOB，暂不支持编辑');
    }
    if (type.contains('INT')) {
      final value = int.tryParse(raw.trim());
      if (value == null) {
        throw FormatException('${column.name} 需要整数');
      }
      return value;
    }
    if (type.contains('REAL') ||
        type.contains('FLOA') ||
        type.contains('DOUB') ||
        type.contains('NUM') ||
        type.contains('DEC')) {
      final value = double.tryParse(raw.trim());
      if (value == null) {
        throw FormatException('${column.name} 需要数字');
      }
      return value;
    }
    return raw;
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: 560,
          maxHeight: MediaQuery.sizeOf(context).height * 0.82,
        ),
        child: Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.18),
                blurRadius: 40,
                offset: const Offset(0, 20),
              ),
            ],
          ),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _isEditing ? '编辑行' : '新增行',
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: Color(0xDD000000),
                        ),
                      ),
                    ),
                    IconButton(
                      onPressed:
                          _saving ? null : () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.close_rounded, size: 20),
                      color: Colors.black45,
                      tooltip: '关闭',
                    ),
                  ],
                ),
                Text(
                  widget.table,
                  style: TextStyle(
                    fontSize: 11,
                    color: Colors.black.withValues(alpha: 0.45),
                  ),
                ),
                const SizedBox(height: 14),
                for (final column in _displayColumns) _buildField(column),
                if (_error != null) ...[
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    decoration: BoxDecoration(
                      color: _danger.withValues(alpha: 0.08),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: _danger.withValues(alpha: 0.25),
                      ),
                    ),
                    child: Text(
                      _error!,
                      style: const TextStyle(fontSize: 12, color: _danger),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed:
                          _saving ? null : () => Navigator.of(context).pop(),
                      child: const Text('取消'),
                    ),
                    const SizedBox(width: 8),
                    FilledButton.icon(
                      onPressed: _saving ? null : _save,
                      icon: _saving
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : const Icon(Icons.save_rounded, size: 18),
                      label: Text(_saving ? '保存中...' : '保存'),
                      style: FilledButton.styleFrom(
                        backgroundColor: _accent,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 18,
                          vertical: 12,
                        ),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildField(SqliteColumn column) {
    final disabled = (_isEditing && column.isPrimaryKey) ||
        column.type.toUpperCase().contains('BLOB');
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                column.name,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: Color(0xDD000000),
                ),
              ),
              const SizedBox(width: 6),
              Text(
                column.type,
                style: TextStyle(
                  fontSize: 10.5,
                  color: Colors.black.withValues(alpha: 0.40),
                ),
              ),
              if (column.isPrimaryKey) ...[
                const SizedBox(width: 6),
                _badge('PK', _accent),
              ],
              if (column.notNull) ...[
                const SizedBox(width: 6),
                _badge('NOT NULL', _danger),
              ],
            ],
          ),
          const SizedBox(height: 6),
          TextField(
            controller: _controllers[column.name],
            enabled: !disabled,
            style: const TextStyle(fontSize: 13, color: Color(0xDD000000)),
            decoration: InputDecoration(
              isDense: true,
              filled: true,
              fillColor: disabled
                  ? Colors.black.withValues(alpha: 0.04)
                  : const Color(0xFFF8FAFC),
              hintText: column.isAutoInteger
                  ? '留空自动生成'
                  : column.defaultValue != null
                      ? '默认: ${column.defaultValue}'
                      : column.notNull
                          ? '必填'
                          : '可空',
              contentPadding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide(
                  color: Colors.black.withValues(alpha: 0.12),
                ),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide(
                  color: Colors.black.withValues(alpha: 0.12),
                ),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: const BorderSide(color: _accent, width: 1.4),
              ),
              disabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide(
                  color: Colors.black.withValues(alpha: 0.08),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _badge(String text, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 9.5,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }
}

class _CsvImportOptionsDialog extends StatefulWidget {
  final String fileName;
  final String table;

  const _CsvImportOptionsDialog({
    required this.fileName,
    required this.table,
  });

  @override
  State<_CsvImportOptionsDialog> createState() =>
      _CsvImportOptionsDialogState();
}

class _CsvImportOptionsDialogState extends State<_CsvImportOptionsDialog> {
  static const _accent = Color(0xFF3B82F6);

  bool _hasHeader = true;
  bool _emptyAsNull = true;
  bool _replaceOnConflict = true;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('导入 CSV'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '文件：${widget.fileName}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12),
            ),
            Text(
              '目标表：${widget.table}',
              style: const TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 8),
            CheckboxListTile(
              value: _hasHeader,
              onChanged: (value) =>
                  setState(() => _hasHeader = value ?? true),
              title: const Text('第一行是表头', style: TextStyle(fontSize: 13)),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
            CheckboxListTile(
              value: _emptyAsNull,
              onChanged: (value) =>
                  setState(() => _emptyAsNull = value ?? true),
              title: const Text('空字符串作为 NULL', style: TextStyle(fontSize: 13)),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
            CheckboxListTile(
              value: _replaceOnConflict,
              onChanged: (value) =>
                  setState(() => _replaceOnConflict = value ?? true),
              title: const Text('主键冲突时替换', style: TextStyle(fontSize: 13)),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(
            context,
            CsvImportOptions(
              hasHeader: _hasHeader,
              emptyAsNull: _emptyAsNull,
              replaceOnConflict: _replaceOnConflict,
            ),
          ),
          style: FilledButton.styleFrom(backgroundColor: _accent),
          child: const Text('开始导入'),
        ),
      ],
    );
  }
}

class _ImportProgressDialog extends StatelessWidget {
  const _ImportProgressDialog();

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      child: Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(20),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.18),
              blurRadius: 40,
              offset: const Offset(0, 20),
            ),
          ],
        ),
        child: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              width: 28,
              height: 28,
              child: CircularProgressIndicator(strokeWidth: 2.6),
            ),
            SizedBox(width: 16),
            Text(
              '正在导入 CSV...',
              style: TextStyle(fontSize: 14, color: Color(0xDD000000)),
            ),
          ],
        ),
      ),
    );
  }
}
