import 'package:flutter/material.dart';

import 'package:humanoid_flutter/pages/home/widgets/home_item_title.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';
import 'package:humanoid_flutter/utils/bot/bot_service.dart';
import 'package:humanoid_flutter/utils/database_util.dart';

class ManagementPage extends StatefulWidget {
  const ManagementPage({super.key});

  @override
  State<ManagementPage> createState() => _ManagementPageState();
}

class _ManagementPageState extends State<ManagementPage> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);

  final BotRepository _repository = BotRepository();
  bool _loading = true;
  bool _busy = false;
  List<Map<String, Object?>> _friends = const [];
  List<Map<String, Object?>> _tasks = const [];
  List<Map<String, Object?>> _reminders = const [];
  List<Map<String, Object?>> _videos = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (!DatabaseUtil.instance.isInitialized) {
      await DatabaseUtil.instance.init();
    }
    try {
      final friends = await _repository.listFriendRequests();
      final tasks = await _repository.listAllTasks();
      final reminders = await _repository.listAllReminders();
      final videos = await _repository.listAllVideoQueue();
      if (!mounted) {
        return;
      }
      setState(() {
        _friends = friends;
        _tasks = tasks;
        _reminders = reminders;
        _videos = videos;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() => _loading = false);
      _show('读取管理数据失败: $e');
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) {
      return;
    }
    setState(() => _busy = true);
    try {
      await action();
      await _load();
    } catch (e) {
      _show('操作失败: $e');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  void _show(String message) {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              HomeItemTitle(
                title: '管理',
                onReFresh: _loading || _busy ? null : _load,
              ),
              const SizedBox(height: 10),
              TabBar(
                isScrollable: true,
                labelColor: _accent,
                unselectedLabelColor: Colors.black.withValues(alpha: 0.55),
                indicatorColor: _accent,
                tabs: [
                  Tab(text: '好友申请 ${_friends.length}'),
                  Tab(text: '任务 ${_tasks.length}'),
                  Tab(text: '提醒 ${_reminders.length}'),
                  Tab(text: '视频 ${_videos.length}'),
                ],
              ),
              const SizedBox(height: 8),
              Expanded(
                child: _loading
                    ? const Center(
                        child: CircularProgressIndicator(strokeWidth: 2.4),
                      )
                    : TabBarView(
                        children: [
                          _friendsView(),
                          _tasksView(),
                          _remindersView(),
                          _videosView(),
                        ],
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _friendsView() {
    if (_friends.isEmpty) {
      return _emptyView('暂无好友申请');
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.only(bottom: 16),
        itemCount: _friends.length,
        separatorBuilder: (_, _) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          final row = _friends[index];
          final id = row['id'] as int?;
          final flag = _text(row, 'flag');
          final status = _text(row, 'status');
          return _card(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        'QQ ${_text(row, 'user_id')}',
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    _statusChip(status),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'flag: $flag',
                  style: TextStyle(
                    fontSize: 11.5,
                    color: Colors.black.withValues(alpha: 0.50),
                  ),
                ),
                if (_text(row, 'comment').isNotEmpty)
                  _subtitle('验证消息：${_text(row, 'comment')}'),
                if (_text(row, 'via').isNotEmpty)
                  _subtitle('来源：${_text(row, 'via')}'),
                if (status == 'PENDING' && id != null) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    children: [
                      OutlinedButton(
                        onPressed: _busy
                            ? null
                            : () => _run(
                                  () => BotService.instance.handleFriendRequest(
                                    id: id,
                                    flag: flag,
                                    approve: true,
                                  ),
                                ),
                        child: const Text('同意'),
                      ),
                      OutlinedButton(
                        onPressed: _busy
                            ? null
                            : () => _run(
                                  () => BotService.instance.handleFriendRequest(
                                    id: id,
                                    flag: flag,
                                    approve: false,
                                  ),
                                ),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: _danger,
                        ),
                        child: const Text('拒绝'),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _tasksView() {
    if (_tasks.isEmpty) {
      return _emptyView('暂无任务');
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.only(bottom: 16),
        itemCount: _tasks.length,
        separatorBuilder: (_, _) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          final row = _tasks[index];
          final id = row['id'] as int?;
          final status = _text(row, 'status');
          final title = _text(row, 'title');
          final groupId = _text(row, 'group_id');
          return _card(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        '任务 #$id「$title」',
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    _statusChip(status),
                  ],
                ),
                _subtitle(
                  'QQ ${_text(row, 'user_id')} · ${_text(row, 'message_type')}'
                  '${groupId.isEmpty ? '' : ' · 群 $groupId'}',
                ),
                if (_text(row, 'pause_reason').isNotEmpty)
                  _subtitle('暂停原因：${_text(row, 'pause_reason')}'),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: [
                    if (id != null)
                      TextButton(
                        onPressed: () => _showTaskDetail(id),
                        child: const Text('详情'),
                      ),
                    if (id != null && status != 'IN_PROGRESS' && status != 'COMPLETED')
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(
                                  () => _repository.updateTaskStatus(
                                    id,
                                    'IN_PROGRESS',
                                  ),
                                ),
                        child: const Text('开始'),
                      ),
                    if (id != null && status == 'IN_PROGRESS')
                      TextButton(
                        onPressed: _busy ? null : () => _pauseTask(id),
                        child: const Text('暂停'),
                      ),
                    if (id != null && status == 'PAUSED')
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(
                                  () => _repository.updateTaskStatus(
                                    id,
                                    'IN_PROGRESS',
                                  ),
                                ),
                        child: const Text('恢复'),
                      ),
                    if (id != null && status != 'COMPLETED')
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(() => _repository.completeTask(id)),
                        child: const Text('完成'),
                      ),
                    if (id != null)
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _deleteTask(id, title),
                        style: TextButton.styleFrom(foregroundColor: _danger),
                        child: const Text('删除'),
                      ),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _remindersView() {
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.only(bottom: 16),
        children: [
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.icon(
              onPressed: _busy ? null : _createReminder,
              icon: const Icon(Icons.add_rounded, size: 18),
              label: const Text('新建提醒'),
            ),
          ),
          const SizedBox(height: 10),
          if (_reminders.isEmpty)
            SizedBox(
              height: 240,
              child: Center(
                child: Text(
                  '暂无提醒',
                  style: TextStyle(
                    fontSize: 13,
                    color: Colors.black.withValues(alpha: 0.50),
                  ),
                ),
              ),
            )
          else
            for (final row in _reminders) ...[
              _reminderCard(row),
              const SizedBox(height: 10),
            ],
        ],
      ),
    );
  }

  Widget _videosView() {
    if (_videos.isEmpty) {
      return _emptyView('暂无视频队列');
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.only(bottom: 16),
        itemCount: _videos.length,
        separatorBuilder: (_, _) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          final row = _videos[index];
          final id = row['id'] as int?;
          final status = _text(row, 'status');
          final title = _text(row, 'title');
          return _card(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title.isEmpty ? _text(row, 'url') : title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    _statusChip(status),
                  ],
                ),
                _subtitle(
                  'QQ ${_text(row, 'user_id')} · ${_text(row, 'message_type')}'
                  ' · 尝试 ${_text(row, 'attempts')} 次',
                ),
                _subtitle(_text(row, 'url')),
                if (_text(row, 'comment').isNotEmpty)
                  _subtitle('推荐语：${_text(row, 'comment')}'),
                if (id != null) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(
                                  () async {
                                    final ok = await BotService.instance
                                        .sendVideoNow(id);
                                    if (!ok) {
                                      throw StateError('发送失败，请稍后重试');
                                    }
                                  },
                                ),
                        child: const Text('立即发送'),
                      ),
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(() => _repository.skipVideo(id)),
                        child: const Text('跳过'),
                      ),
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(() => _repository.requeueVideo(id)),
                        child: const Text('重新入队'),
                      ),
                      TextButton(
                        onPressed: _busy
                            ? null
                            : () => _run(() => _repository.deleteVideo(id)),
                        style: TextButton.styleFrom(foregroundColor: _danger),
                        child: const Text('删除'),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _reminderCard(Map<String, Object?> row) {
    final id = row['id'] as int?;
    final status = _text(row, 'status');
    final content = _text(row, 'content');
    return _card(
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  content.length > 60 ? '${content.substring(0, 60)}…' : content,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              _statusChip(status),
            ],
          ),
          _subtitle(
            'QQ ${_text(row, 'user_id')} · ${_text(row, 'message_type')}'
            ' · ${_formatTime(row['remind_at'] as int?)}',
          ),
          if (id != null) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => _run(() async {
                            await BotService.instance.triggerReminderNow(id);
                          }),
                  child: const Text('立即触发'),
                ),
                if (status != 'PENDING')
                  TextButton(
                    onPressed: _busy
                        ? null
                        : () => _run(() => _repository.retryReminder(id)),
                    child: const Text('重试'),
                  ),
                if (status == 'PENDING')
                  TextButton(
                    onPressed: _busy
                        ? null
                        : () => _run(() => _repository.cancelReminder(id)),
                    child: const Text('取消'),
                  ),
                TextButton(
                  onPressed: _busy
                      ? null
                      : () => _run(() => _repository.deleteReminder(id)),
                  style: TextButton.styleFrom(foregroundColor: _danger),
                  child: const Text('删除'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _pauseTask(int id) async {
    final controller = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('暂停任务'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(hintText: '暂停原因'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('确定'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (reason == null || reason.isEmpty) {
      return;
    }
    await _run(
      () => _repository.updateTaskStatus(id, 'PAUSED', pauseReason: reason),
    );
  }

  Future<void> _showTaskDetail(int id) async {
    final task = await _repository.getTask(id);
    final steps = await _repository.taskSteps(id);
    if (!mounted) {
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('任务 #$id「${_text(task ?? const <String, Object?>{}, 'title')}」'),
        content: SizedBox(
          width: 520,
          child: steps.isEmpty
              ? const Text('暂无步骤')
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (final step in steps)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 3),
                        child: Text(
                          '${step['step_no']}. '
                          '${step['status'] == 'COMPLETED' ? '[x]' : '[ ]'} '
                          '${step['content']}',
                        ),
                      ),
                  ],
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  Future<void> _deleteTask(int id, String title) async {
    final confirmed = await _confirm('删除任务 #$id「$title」？');
    if (!confirmed) {
      return;
    }
    await _run(() => _repository.deleteTask(id));
  }

  Future<void> _createReminder() async {
    final draft = await showDialog<_ReminderDraft>(
      context: context,
      builder: (_) => const _CreateReminderDialog(),
    );
    if (draft == null) {
      return;
    }
    final remindAt = _parseDateTime(draft.remindAt);
    if (remindAt == null) {
      _show('时间格式不正确，请使用 yyyy-MM-dd HH:mm');
      return;
    }
    await _run(() async {
      final scope = BotScope(
        userId: draft.userId,
        messageType: draft.groupId == null ? 'private' : 'group',
        groupId: draft.groupId,
      );
      await _repository.addReminder(
        scope,
        draft.content,
        remindAt.millisecondsSinceEpoch,
      );
    });
  }

  Future<bool> _confirm(String message) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认操作'),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确定'),
          ),
        ],
      ),
    );
    return result == true;
  }

  Widget _emptyView(String text) {
    return ListView(
      children: [
        SizedBox(
          height: 240,
          child: Center(
            child: Text(
              text,
              style: TextStyle(
                fontSize: 13,
                color: Colors.black.withValues(alpha: 0.50),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _card(Widget child) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withValues(alpha: 0.80)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.06),
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: child,
    );
  }

  Widget _subtitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Text(
        text,
        maxLines: 3,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 11.5,
          color: Colors.black.withValues(alpha: 0.50),
        ),
      ),
    );
  }

  Widget _statusChip(String status) {
    final color = switch (status) {
      'PENDING' => Colors.orange,
      'IN_PROGRESS' => Colors.blue,
      'PAUSED' => Colors.deepOrange,
      'COMPLETED' || 'SENT' || 'APPROVED' => Colors.green,
      'FAILED' || 'REJECTED' || 'CANCELED' => _danger,
      'SKIPPED' => Colors.grey,
      _ => Colors.blueGrey,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        status,
        style: TextStyle(
          fontSize: 10.5,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }

  static String _text(Map<String, Object?> row, String key) {
    final value = row[key];
    if (value == null || value == '' || value == 'null') {
      return '';
    }
    return value.toString();
  }

  static String _formatTime(int? millis) {
    if (millis == null) {
      return '-';
    }
    final time = DateTime.fromMillisecondsSinceEpoch(millis);
    String two(int value) => value.toString().padLeft(2, '0');
    return '${time.year}-${two(time.month)}-${two(time.day)} '
        '${two(time.hour)}:${two(time.minute)}';
  }

  static DateTime? _parseDateTime(String text) {
    final value = text.trim();
    if (value.isEmpty) {
      return null;
    }
    final parsed = DateTime.tryParse(value.replaceFirst(' ', 'T'));
    if (parsed != null) {
      return parsed;
    }
    final match = RegExp(
      r'^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?$',
    ).firstMatch(value);
    if (match == null) {
      return null;
    }
    return DateTime(
      int.parse(match.group(1)!),
      int.parse(match.group(2)!),
      int.parse(match.group(3)!),
      int.parse(match.group(4)!),
      int.parse(match.group(5)!),
      int.tryParse(match.group(6) ?? '') ?? 0,
    );
  }
}

class _ReminderDraft {
  const _ReminderDraft({
    required this.userId,
    required this.groupId,
    required this.content,
    required this.remindAt,
  });

  final int userId;
  final int? groupId;
  final String content;
  final String remindAt;
}

class _CreateReminderDialog extends StatefulWidget {
  const _CreateReminderDialog();

  @override
  State<_CreateReminderDialog> createState() => _CreateReminderDialogState();
}

class _CreateReminderDialogState extends State<_CreateReminderDialog> {
  final _userId = TextEditingController();
  final _groupId = TextEditingController();
  final _content = TextEditingController();
  final _remindAt = TextEditingController();

  @override
  void dispose() {
    _userId.dispose();
    _groupId.dispose();
    _content.dispose();
    _remindAt.dispose();
    super.dispose();
  }

  void _submit() {
    final userId = int.tryParse(_userId.text.trim());
    if (userId == null) {
      _show('请输入正确的 QQ 号');
      return;
    }
    final content = _content.text.trim();
    if (content.isEmpty) {
      _show('提醒内容不能为空');
      return;
    }
    final remindAt = _remindAt.text.trim();
    if (remindAt.isEmpty) {
      _show('请输入提醒时间');
      return;
    }
    final groupText = _groupId.text.trim();
    int? groupId;
    if (groupText.isNotEmpty) {
      groupId = int.tryParse(groupText);
      if (groupId == null) {
        _show('群号格式不正确');
        return;
      }
    }
    Navigator.pop(
      context,
      _ReminderDraft(
        userId: userId,
        groupId: groupId,
        content: content,
        remindAt: remindAt,
      ),
    );
  }

  void _show(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('新建提醒'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _userId,
              decoration: const InputDecoration(labelText: 'QQ 号'),
            ),
            TextField(
              controller: _groupId,
              decoration: const InputDecoration(
                labelText: '群号（可选）',
                hintText: '留空表示私聊提醒',
              ),
            ),
            TextField(
              controller: _content,
              minLines: 3,
              maxLines: 6,
              decoration: const InputDecoration(labelText: '提醒内容'),
            ),
            TextField(
              controller: _remindAt,
              decoration: const InputDecoration(
                labelText: '提醒时间',
                hintText: '例如 2026-11-05 09:30',
              ),
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
          onPressed: _submit,
          child: const Text('创建'),
        ),
      ],
    );
  }
}
