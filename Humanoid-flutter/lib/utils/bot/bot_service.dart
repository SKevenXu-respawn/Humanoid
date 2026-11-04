import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../../config/AppEnum.dart';
import '../database_util.dart';
import '../terminal/terminal_util.dart';
import 'bot_agent_service.dart';
import 'bot_asr_client.dart';
import 'bot_memory_summary_service.dart';
import 'bot_models.dart';
import 'bot_paths.dart';
import 'bot_skill_service.dart';
import 'bot_summary_service.dart';
import 'bot_tools.dart';
import 'llbot_api_client.dart';

class BotService {
  BotService._();

  static final BotService instance = BotService._();

  final ValueNotifier<BotStatus> statusNotifier =
      ValueNotifier(BotStatus.stopped);

  HttpServer? _server;
  BotRepository? _repository;
  LlbotApiClient? _api;
  BotAgentService? _agent;
  BotSummaryService? _summaryService;
  final Map<String, Future<void>> _queues = {};
  final Set<int> _seenMessageIds = {};
  final List<int> _seenOrder = [];
  final Map<int, DateTime> _lastRejectAt = {};
  static const _rejectMessage = '你没在白名单上，和开发者聊聊';
  static const _rejectCooldown = Duration(minutes: 10);
  Timer? _reminderTimer;
  Timer? _taskTimer;
  Timer? _proactiveTimer;
  Timer? _videoTimer;
  Timer? _memoryTimer;
  bool _executingTasks = false;
  bool _proactiveRunning = false;
  bool _sendingVideo = false;

  int _eventPort = 13001;
  String _eventIP='192.168.1.107';
  String _apiBaseUrl = 'http://127.0.0.1:3000';
  bool _starting = false;

  bool get isRunning => _server != null;

  int get eventPort => _eventPort;

  String get apiBaseUrl => _apiBaseUrl;

  String get eventUrl => 'http://$_eventIP:$_eventPort/api/llbot/events';

  Future<void> start() async {
    if (_server != null || _starting) {
      return;
    }
    _starting = true;
    statusNotifier.value = BotStatus.starting;
    _log('正在启动机器人服务...');

    try {
      await DatabaseUtil.instance.init();
      await BotPaths.init();

      final configuredPort =
          int.tryParse(await DatabaseUtil.instance.getSetting('bot_event_port') ?? '');
      _eventPort = configuredPort ?? 13001;
      _apiBaseUrl =
          (await DatabaseUtil.instance.getSetting('llbot_api_base'))?.trim() ??
              '';
      if (_apiBaseUrl.isEmpty) {
        _apiBaseUrl = 'http://127.0.0.1:3000';
      }
      final configuredToken =
          (await DatabaseUtil.instance.getSecretValue('llonebot_key'))?.trim() ??
              '';
      final legacyToken =
          (await DatabaseUtil.instance.getSecretValue('llbot_api_token'))
                  ?.trim() ??
              '';
      final token = configuredToken.isNotEmpty ? configuredToken : legacyToken;

      _repository = BotRepository();
      _api = LlbotApiClient(baseUrl: _apiBaseUrl, token: token);
      final skills = BotSkillService();
      final toolRegistry = BotToolRegistry(
        repository: _repository!,
        api: _api!,
        skills: skills,
      );
      _agent = BotAgentService(
        repository: _repository!,
        tools: toolRegistry,
        skills: skills,
      );
      _summaryService = BotSummaryService(repository: _repository!);

      _server = await HttpServer.bind(InternetAddress.anyIPv4, _eventPort);
      _server!.listen((request) {
        unawaited(
          _handleRequest(request).catchError((Object error) {
            _log('处理事件请求异常: $error');
          }),
        );
      });
      _startBackgroundTimers();
      statusNotifier.value = BotStatus.running;
      _log('机器人服务已启动，监听 $_eventIP:$_eventPort');
      _log('LLBot API: $_apiBaseUrl');
    } catch (e) {
      _server = null;
      statusNotifier.value = BotStatus.error;
      _log('机器人服务启动失败: $e');
    } finally {
      _starting = false;
    }
  }

  Future<void> stop() async {
    _cancelTimers();
    final server = _server;
    _server = null;
    if (server != null) {
      try {
        await server.close(force: true);
      } catch (_) {
        // ignore close errors
      }
    }
    statusNotifier.value = BotStatus.stopped;
    _log('机器人服务已停止');
  }

  Future<void> restart() async {
    await stop();
    await start();
  }

  Future<void> triggerReminderNow(int id) async {
    final repository = _repository;
    if (repository == null) {
      throw StateError('机器人服务未启动');
    }
    await repository.retryReminder(id, immediate: true);
    await _dispatchReminders();
  }

  Future<bool> sendVideoNow(int id) async {
    final repository = _repository;
    final api = _api;
    if (repository == null || api == null) {
      throw StateError('机器人服务未启动');
    }
    final video = await repository.getVideo(id);
    if (video == null) {
      return false;
    }
    final scope = _scopeFromRow(video);
    if (scope == null) {
      return false;
    }
    if (!await repository.claimVideo(
      id,
      video['attempts'] as int? ?? 0,
    )) {
      return false;
    }
    final title = video['title']?.toString().trim() ?? '';
    final comment = video['comment']?.toString().trim() ?? '';
    final text = [
      '看到一个有意思的视频，分享给你～',
      if (title.isNotEmpty) title,
      video['url']?.toString() ?? '',
      if (comment.isNotEmpty) comment,
    ].join('\n');
    final ok = await _notifyScope(api, scope, text);
    if (ok) {
      await repository.markVideoSent(id);
    } else {
      await repository.markVideoFailed(id);
    }
    return ok;
  }

  Future<void> handleFriendRequest({
    required int id,
    required String flag,
    required bool approve,
    String remark = '',
  }) async {
    final repository = _repository;
    final api = _api;
    if (repository == null || api == null) {
      throw StateError('机器人服务未启动');
    }
    final ok = await api.setFriendAddRequest(
      flag: flag,
      approve: approve,
      remark: remark,
    );
    if (!ok) {
      throw StateError('LLBot 处理好友申请失败');
    }
    await repository.updateFriendRequestStatus(
      id,
      approve ? 'APPROVED' : 'REJECTED',
      remark: remark,
    );
  }

  Future<void> _handleRequest(HttpRequest request) async {
    if (request.method != 'POST') {
      request.response.statusCode = HttpStatus.notFound;
      await request.response.close();
      return;
    }
    _log('收到事件上报: ${request.uri.path}');

    try {
      final payload = await const Utf8Decoder(
        allowMalformed: true,
      ).bind(request).join();
      final decoded = jsonDecode(payload);
      if (decoded is! Map) {
        request.response.statusCode = HttpStatus.badRequest;
        await request.response.close();
        return;
      }
      final event = BotEvent.parse(Map<String, dynamic>.from(decoded));
      request.response.statusCode = HttpStatus.ok;
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode({'status': 'ok', 'retcode': 0}));
      await request.response.close();

      if (event != null) {
        unawaited(_onEvent(event));
      }
    } catch (e) {
      _log('解析 LLBot 事件失败: $e');
      try {
        request.response.statusCode = HttpStatus.badRequest;
        await request.response.close();
      } catch (_) {
        // ignore response errors
      }
    }
  }

  Future<void> _onEvent(BotEvent event) async {
    final repository = _repository;
    if (repository == null) {
      return;
    }

    if (event.postType == 'request') {
      await _recordFriendRequest(event);
      return;
    }
    if (event.postType != 'message') {
      return;
    }

    final userId = event.userId;
    if (userId == null || userId == event.selfId) {
      return;
    }
    final scope = event.scope;
    if (scope == null) {
      return;
    }

    if (!await repository.isUserAllowed(scope.userId)) {
      _log('忽略非白名单用户 ${scope.userId}');
      if (!scope.isGroup) {
        await _rejectUnknownUser(scope.userId);
      }
      return;
    }
    if (scope.isGroup && !await repository.isGroupAllowed(scope.scopeGroupId)) {
      _log('忽略非白名单群 ${scope.scopeGroupId}');
      return;
    }

    final messageId = event.messageId;
    if (messageId != null) {
      if (_seenMessageIds.contains(messageId)) {
        return;
      }
      _seenMessageIds.add(messageId);
      _seenOrder.add(messageId);
      if (_seenOrder.length > 500) {
        _seenMessageIds.remove(_seenOrder.removeAt(0));
      }
    }

    if (event.text.isEmpty &&
        event.imageRefs.isEmpty &&
        event.recordFile == null &&
        event.replyMessageId == null) {
      _log('收到空消息，忽略');
      return;
    }

    await repository.refreshBotUserNickname(scope.userId, event.nickname);

    final preview = event.text.isNotEmpty
        ? event.text
        : event.recordFile != null
            ? '[语音]'
            : '[图片]';
    _log(
      '[${scope.isGroup ? '群 ${scope.groupId}' : '私聊 ${scope.userId}'}] '
      '${event.nickname ?? scope.userId}: $preview',
    );

    await _enqueue(scope, () => _process(event, scope));
  }

  Future<void> _recordFriendRequest(BotEvent event) async {
    final repository = _repository;
    if (repository == null ||
        event.raw['request_type']?.toString() != 'friend') {
      return;
    }
    final userId = _asInt(event.raw['user_id']);
    final flag = event.raw['flag']?.toString().trim() ?? '';
    if (userId == null || flag.isEmpty) {
      return;
    }
    final saved = await repository.recordFriendRequest(
      userId: userId,
      flag: flag,
      comment: event.raw['comment']?.toString(),
      via: event.raw['via']?.toString(),
      eventTimeSeconds: _asInt(event.raw['time']),
    );
    if (saved) {
      _log('收到好友申请 user=$userId');
    }
  }

  Future<void> _rejectUnknownUser(int userId) async {
    final api = _api;
    if (api == null) {
      return;
    }
    final now = DateTime.now();
    final last = _lastRejectAt[userId];
    if (last != null && now.difference(last) < _rejectCooldown) {
      return;
    }
    _lastRejectAt[userId] = now;
    _lastRejectAt.removeWhere(
      (key, value) => now.difference(value) > const Duration(days: 1),
    );
    final result = await api.sendPrivate(userId, _rejectMessage);
    if (result.ok) {
      _log('已提示非白名单用户 $userId');
    } else {
      _log('非白名单提示发送失败 user=$userId: ${result.error ?? '未知错误'}');
    }
  }

  static int? _asInt(Object? value) {
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.toInt();
    }
    return int.tryParse(value?.toString() ?? '');
  }

  Future<String> _messageTextWithVoice(
    LlbotApiClient api,
    BotEvent event,
  ) async {
    final text = await _messageText(api, event);
    final file = event.recordFile;
    if (file == null || file.isEmpty) {
      return text;
    }
    final voice = await _transcribeVoice(api, event, file);
    if (text.isEmpty || text == '[图片]') {
      return voice;
    }
    return '$text\n$voice';
  }

  Future<String> _transcribeVoice(
    LlbotApiClient api,
    BotEvent event,
    String file,
  ) async {
    final asr = await BotAsrClient.load();
    if (asr != null) {
      final base64 = await api.getRecordBase64(file);
      if (base64 != null && base64.isNotEmpty) {
        try {
          final encoded = _stripDataUrl(base64);
          if (encoded.length <= 14_000_000) {
            final text = await asr.transcribe(base64Decode(encoded));
            if (text != null && text.isNotEmpty) {
              _log('语音转文字成功（豆包）: ${_abbrev(text, 80)}');
              return '[语音转文字] $text';
            }
          }
        } catch (e) {
          _log('语音识别失败: ${_abbrev('$e', 120)}');
        }
      }
    }

    final messageId = event.messageId;
    if (messageId != null) {
      final text = await api.voiceMsgToText(messageId);
      if (text != null && text.isNotEmpty) {
        _log('语音转文字成功（LLBot）: ${_abbrev(text, 80)}');
        return '[语音转文字] $text';
      }
    }
    _log('语音转文字失败 record=$file');
    return '[语音消息]（语音转文字失败）';
  }

  static String _stripDataUrl(String value) {
    final text = value.trim();
    if (text.startsWith('base64://')) {
      return text.substring('base64://'.length);
    }
    if (text.startsWith('data:')) {
      final comma = text.indexOf(',');
      if (comma > 0) {
        return text.substring(comma + 1);
      }
    }
    return text;
  }

  Future<String> _messageText(LlbotApiClient api, BotEvent event) async {
    final base = event.text.isNotEmpty
        ? event.text
        : (event.imageRefs.isEmpty ? '' : '[图片]');
    final replyId = event.replyMessageId;
    if (replyId == null) {
      return base;
    }
    final quoted = await api.getMessageText(replyId);
    if (quoted == null || quoted.trim().isEmpty) {
      return base;
    }
    final prefix = '[引用] ${quoted.trim()}';
    return base.isEmpty ? prefix : '$prefix\n$base';
  }

  Future<void> _enqueue(
    BotScope scope,
    Future<void> Function() action,
  ) {
    final previous = _queues[scope.key] ?? Future<void>.value();
    final next = previous.then((_) => action());
    _queues[scope.key] = next.catchError((Object _) {});
    return next;
  }

  Future<void> _process(BotEvent event, BotScope scope) async {
    final repository = _repository;
    final api = _api;
    final agent = _agent;
    if (repository == null || api == null || agent == null) {
      return;
    }

    final messageText = await _messageTextWithVoice(api, event);
    final userConversationId = await repository.appendConversation(
      scope: scope,
      role: 'user',
      content: messageText,
      onebotMessageId: event.messageId,
      messageSeq: event.messageSeq,
      nickname: event.nickname,
      segments: event.segmentsJson,
      eventTimeSeconds: event.eventTimeSeconds,
    );
    final contextId = await repository.appendContext(
      scope: scope,
      role: 'user',
      content: messageText,
      imageRefs: event.imageRefs,
    );
    final summaryService = _summaryService;
    if (summaryService != null) {
      await summaryService.maybeSummarize(
        scope,
        beforeConversationId: userConversationId,
      );
    }

    try {
      final images = await _loadImages(event.imageRefs);
      final reply = await agent.generateReply(
        scope: scope,
        userText: messageText,
        currentContextId: contextId,
        images: images,
      );
      final result = await _sendReply(api, scope, reply);
      if (!result.ok) {
        _log(
          '回复发送失败（${reply.length} 字）: '
          '${result.error ?? '未知错误'}',
        );
        return;
      }
      await repository.appendConversation(
        scope: scope,
        role: 'bot',
        content: reply,
        onebotMessageId: result.messageId,
      );
      await repository.appendContext(
        scope: scope,
        role: 'assistant',
        content: reply,
      );
      _log('已回复: ${_abbrev(reply, 120)}');
    } on BotConfigException catch (e) {
      _log('机器人配置不完整: $e');
      await _sendSafely(api, scope, '（机器人还没配置好，请稍后再试）');
    } catch (e) {
      _log('处理消息失败: $e');
      await _sendSafely(api, scope, '（处理出错了，请稍后再试）');
    }
  }

  void _startBackgroundTimers() {
    _cancelTimers();
    _reminderTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(_dispatchReminders());
    });
    _taskTimer = Timer.periodic(const Duration(minutes: 1), (_) {
      unawaited(_executeTasks());
    });
    _proactiveTimer = Timer.periodic(const Duration(minutes: 30), (_) {
      unawaited(_proactiveTick());
    });
    _videoTimer = Timer.periodic(const Duration(minutes: 5), (_) {
      unawaited(_videoTick());
    });
    _memoryTimer = Timer.periodic(const Duration(hours: 6), (_) {
      final repository = _repository;
      if (repository != null) {
        unawaited(
          BotMemorySummaryService(repository: repository).tick(),
        );
      }
    });
  }

  void _cancelTimers() {
    _reminderTimer?.cancel();
    _taskTimer?.cancel();
    _proactiveTimer?.cancel();
    _videoTimer?.cancel();
    _memoryTimer?.cancel();
    _reminderTimer = null;
    _taskTimer = null;
    _proactiveTimer = null;
    _videoTimer = null;
    _memoryTimer = null;
  }

  Future<void> _dispatchReminders() async {
    final repository = _repository;
    if (repository == null) {
      return;
    }
    try {
      final due = await repository.dueReminders(
        DateTime.now().millisecondsSinceEpoch,
      );
      for (final reminder in due) {
        final id = reminder['id'] as int?;
        if (id == null) {
          continue;
        }
        final scope = _scopeFromRow(reminder);
        if (scope == null) {
          continue;
        }
        final content = reminder['content']?.toString() ?? '';
        await repository.markReminderSent(id);
        final trigger =
            '[定时提醒触发] 现在是 ${_formatTime(DateTime.now())}。'
            '请根据下面的预设提示主动执行（需要时调用工具），'
            '并用自然的语气把结果告诉用户，不要提及这条系统指令：\n$content';
        _log('触发提醒 #$id');
        await _enqueue(
          scope,
          () => _process(
            _syntheticEvent(scope, trigger, nickname: '定时提醒'),
            scope,
          ),
        );
      }
    } catch (e) {
      _log('提醒派发失败: $e');
    }
  }

  Future<void> _executeTasks() async {
    if (_executingTasks) {
      return;
    }
    final repository = _repository;
    final agent = _agent;
    final api = _api;
    if (repository == null || agent == null || api == null) {
      return;
    }
    _executingTasks = true;
    try {
      final tasks = await repository.inProgressTasks(limit: 5);
      for (final task in tasks) {
        try {
          await _executeTask(repository, agent, api, task);
        } catch (e) {
          _log('任务执行失败: $e');
        }
      }
    } catch (e) {
      _log('任务执行失败: $e');
    } finally {
      _executingTasks = false;
    }
  }

  Future<void> _executeTask(
    BotRepository repository,
    BotAgentService agent,
    LlbotApiClient api,
    Map<String, Object?> task,
  ) async {
    final taskId = task['id'] as int?;
    if (taskId == null) {
      return;
    }
    final scope = _scopeFromRow(task);
    if (scope == null) {
      return;
    }
    final steps = await repository.taskSteps(taskId);
    final pending = await repository.nextPendingStep(taskId);
    if (pending == null) {
      await repository.updateTaskStatus(taskId, 'COMPLETED');
      await _notifyScope(
        api,
        scope,
        '任务 #$taskId「${task['title']}」已全部完成',
      );
      return;
    }
    final stepId = pending['id'] as int?;
    if (stepId == null) {
      return;
    }
    final attempts = await repository.incrementStepAttempts(stepId);
    if (attempts > 3) {
      await repository.updateTaskStatus(
        taskId,
        'PAUSED',
        pauseReason: '步骤 ${pending['step_no']} 多次执行失败',
      );
      await _notifyScope(
        api,
        scope,
        '任务 #$taskId 已暂停：步骤多次执行失败',
      );
      return;
    }

    final reply = await agent.generateTaskStep(
      scope: scope,
      task: task,
      step: pending,
      steps: steps,
    );
    final afterSteps = await repository.taskSteps(taskId);
    Map<String, Object?> after = pending;
    for (final item in afterSteps) {
      if (item['id'] == stepId) {
        after = item;
        break;
      }
    }
    if (after['status'] == 'COMPLETED') {
      final done =
          afterSteps.where((item) => item['status'] == 'COMPLETED').length;
      await _notifyScope(
        api,
        scope,
        '任务 #$taskId：步骤 ${pending['step_no']}「${pending['content']}」'
        '已完成（$done/${afterSteps.length}）',
      );
      if (done == afterSteps.length) {
        await repository.updateTaskStatus(taskId, 'COMPLETED');
        await _notifyScope(
          api,
          scope,
          '任务 #$taskId「${task['title']}」已全部完成',
        );
      }
    } else if (reply.trim().isNotEmpty) {
      _log(
        '任务 #$taskId 步骤 ${pending['step_no']} 执行输出: '
        '${_abbrev(reply, 120)}',
      );
    }
  }

  Future<void> _proactiveTick() async {
    if (_proactiveRunning) {
      return;
    }
    final repository = _repository;
    final api = _api;
    final agent = _agent;
    if (repository == null || api == null || agent == null) {
      return;
    }
    _proactiveRunning = true;
    try {
      final users = await repository.enabledBotUsers();
      if (users.isEmpty) {
        return;
      }
      final filterRaw =
          await DatabaseUtil.instance.getSetting('proactive_user_ids') ?? '';
      final filter = filterRaw
          .split(',')
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .map(int.tryParse)
          .whereType<int>()
          .toSet();
      final now = DateTime.now();
      for (final user in users) {
        final userId = user['user_id'] as int?;
        if (userId == null) {
          continue;
        }
        if (filter.isNotEmpty && !filter.contains(userId)) {
          continue;
        }
        final state = await repository.proactiveState(userId);
        final lastCheckedAt = state?['last_checked_at'] as int? ?? 0;
        if (now.millisecondsSinceEpoch - lastCheckedAt <
            const Duration(minutes: 25).inMilliseconds) {
          continue;
        }
        final lastUserAt = await repository.lastPrivateUserMessageAt(userId);
        final lastProactiveAt = state?['last_proactive_at'] as int?;
        final awaitingReply = lastProactiveAt != null &&
            (lastUserAt == null ||
                lastProactiveAt >= lastUserAt.millisecondsSinceEpoch);
        final scope = BotScope(userId: userId, messageType: 'private');
        final summary = await repository.getSummary(scope);
        final recent = await repository.recentContext(scope, limit: 10);

        final context = StringBuffer();
        context.writeln('当前时间：${_formatTime(now)}');
        context.writeln(
          lastUserAt == null
              ? '用户还没有发过消息'
              : '用户最后发言：${_formatTime(lastUserAt)}',
        );
        context.writeln('用户尚未回复上次主动消息：${awaitingReply ? '是' : '否'}');
        context.writeln('已有的对话总结：\n${summary.isEmpty ? '无' : summary}');
        final activeTask = await _activeTaskBrief(repository, scope);
        if (activeTask.isNotEmpty) {
          context.writeln('当前未完成任务：\n$activeTask');
        }
        context.writeln('最近对话：');
        for (final row in recent) {
          final role = row['role'] == 'assistant' ? '助手' : '用户';
          context.writeln(
            '$role: ${_abbrev(row['content']?.toString() ?? '', 300)}',
          );
        }

        final decision = await agent.generateProactiveReply(
          scope: scope,
          context: context.toString(),
        );
        final upper = decision.trim().toUpperCase();
        if (upper.startsWith('CHAT:') && !awaitingReply) {
          final rawText = decision.trim().substring(5).trim();
          final text = rawText.length > 500
              ? '${rawText.substring(0, 500)}…'
              : rawText;
          final sent =
              text.isNotEmpty && await _notifyScope(api, scope, text);
          await repository.touchProactiveState(
            userId,
            lastCheckedAt: now.millisecondsSinceEpoch,
            lastProactiveAt:
                sent ? DateTime.now().millisecondsSinceEpoch : null,
          );
          if (sent) {
            _log('主动聊天 user=$userId: ${_abbrev(text, 80)}');
          }
        } else {
          await repository.touchProactiveState(
            userId,
            lastCheckedAt: now.millisecondsSinceEpoch,
          );
          _log('主动行为跳过 user=$userId: ${_abbrev(decision, 40)}');
        }
      }
    } catch (e) {
      _log('主动行为失败: $e');
    } finally {
      _proactiveRunning = false;
    }
  }

  Future<void> _videoTick() async {
    if (_sendingVideo) {
      return;
    }
    final repository = _repository;
    final api = _api;
    if (repository == null || api == null) {
      return;
    }
    _sendingVideo = true;
    try {
      for (var index = 0; index < 5; index++) {
        final video = await repository.nextPendingVideo();
        if (video == null) {
          break;
        }
        final scope = _scopeFromRow(video);
        if (scope == null) {
          break;
        }
        final videoId = video['id'] as int?;
        if (videoId == null) {
          break;
        }
        if (!await repository.claimVideo(
          videoId,
          video['attempts'] as int? ?? 0,
        )) {
          continue;
        }
        final title = video['title']?.toString().trim() ?? '';
        final comment = video['comment']?.toString().trim() ?? '';
        final text = [
          '看到一个有意思的视频，分享给你～',
          if (title.isNotEmpty) title,
          video['url']?.toString() ?? '',
          if (comment.isNotEmpty) comment,
        ].join('\n');
        if (!await _notifyScope(api, scope, text)) {
          await repository.markVideoFailed(videoId);
          break;
        }
        await repository.markVideoSent(
          videoId,
          messageId: null,
        );
        _log('已发送队列视频: ${video['url']}');
      }
    } catch (e) {
      _log('视频队列发送失败: $e');
    } finally {
      _sendingVideo = false;
    }
  }

  Future<String> _activeTaskBrief(
    BotRepository repository,
    BotScope scope,
  ) async {
    final task = await repository.activeTask(scope);
    if (task == null) {
      return '';
    }
    final taskId = task['id'] as int?;
    if (taskId == null) {
      return '';
    }
    final steps = await repository.taskSteps(taskId);
    final done =
        steps.where((step) => step['status'] == 'COMPLETED').length;
    final buffer = StringBuffer(
      '任务 #$taskId「${task['title']}」状态=${task['status']}'
      '（$done/${steps.length}）',
    );
    for (final step in steps) {
      final completed = step['status'] == 'COMPLETED';
      buffer.writeln(
        '\n  ${step['step_no']}. ${completed ? '[x]' : '[ ]'} ${step['content']}',
      );
    }
    return buffer.toString().trimRight();
  }

  Future<bool> _notifyScope(
    LlbotApiClient api,
    BotScope scope,
    String text,
  ) async {
    final result = await _sendReply(api, scope, text);
    if (!result.ok) {
      _log('消息发送失败: ${result.error ?? '未知错误'}');
      return false;
    }
    final repository = _repository;
    if (repository != null) {
      await repository.appendConversation(
        scope: scope,
        role: 'bot',
        content: text,
        onebotMessageId: result.messageId,
      );
      await repository.appendContext(
        scope: scope,
        role: 'assistant',
        content: text,
      );
    }
    return true;
  }

  BotScope? _scopeFromRow(Map<String, Object?> row) {
    final userId = row['user_id'] as int?;
    final messageType = row['message_type']?.toString() ?? 'private';
    if (userId == null) {
      return null;
    }
    final groupId = row['group_id'] as int?;
    return BotScope(
      userId: userId,
      messageType: messageType,
      groupId: messageType == 'group' ? groupId : null,
    );
  }

  BotEvent _syntheticEvent(
    BotScope scope,
    String text, {
    String nickname = '系统',
  }) {
    return BotEvent(
      postType: 'message',
      messageType: scope.messageType,
      userId: scope.userId,
      selfId: null,
      groupId: scope.groupId,
      messageId: null,
      messageSeq: null,
      nickname: nickname,
      text: text,
      imageRefs: const [],
      recordFile: null,
      replyMessageId: null,
      eventTimeSeconds: null,
      raw: const {},
    );
  }

  Future<List<BotImageInput>> _loadImages(List<String> refs) async {
    if (refs.isEmpty) {
      return const [];
    }
    final result = <BotImageInput>[];
    final dio = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 30),
      ),
    );
    for (final ref in refs.take(4)) {
      try {
        var bytes = <int>[];
        var mime = 'image/jpeg';
        if (ref.startsWith('base64://')) {
          bytes = base64Decode(ref.substring('base64://'.length));
        } else if (ref.startsWith('http://') || ref.startsWith('https://')) {
          final response = await dio.get<List<int>>(
            ref,
            options: Options(
              responseType: ResponseType.bytes,
              followRedirects: true,
            ),
          );
          bytes = response.data ?? const [];
          final contentType =
              response.headers.value(Headers.contentTypeHeader);
          if (contentType != null && contentType.trim().isNotEmpty) {
            mime = contentType.split(';').first.trim();
          }
        } else if (File(ref).existsSync()) {
          bytes = await File(ref).readAsBytes();
        } else {
          continue;
        }
        if (bytes.isEmpty || bytes.length > 10 * 1024 * 1024) {
          continue;
        }
        if (!mime.startsWith('image/')) {
          mime = _guessMime(ref);
        }
        result.add(
          BotImageInput(
            base64: base64Encode(bytes),
            mimeType: mime,
          ),
        );
      } catch (e) {
        _log('图片加载失败: ${_abbrev('$e', 120)}');
      }
    }
    return result;
  }

  static String _guessMime(String ref) {
    final lower = ref.toLowerCase();
    if (lower.endsWith('.png')) {
      return 'image/png';
    }
    if (lower.endsWith('.gif')) {
      return 'image/gif';
    }
    if (lower.endsWith('.webp')) {
      return 'image/webp';
    }
    return 'image/jpeg';
  }

  static String _formatTime(DateTime time) {
    String two(int value) => value.toString().padLeft(2, '0');
    return '${time.year}-${two(time.month)}-${two(time.day)} '
        '${two(time.hour)}:${two(time.minute)}';
  }

  Future<LlbotApiResult> _sendReply(
    LlbotApiClient api,
    BotScope scope,
    String text,
  ) {
    final message = _limitMessage(text);
    if (message.length != text.length) {
      _log('回复过长，已截断到 ${message.length} 字后发送');
    }
    if (scope.isGroup) {
      final groupId = scope.groupId;
      if (groupId == null) {
        return Future.value(
          const LlbotApiResult(ok: false, error: '缺少群号'),
        );
      }
      return api.sendGroup(groupId, message);
    }
    return api.sendPrivate(scope.userId, message);
  }

  static String _limitMessage(String text) {
    const max = 4000;
    return text.length <= max ? text : '${text.substring(0, max)}…';
  }

  Future<void> _sendSafely(
    LlbotApiClient api,
    BotScope scope,
    String text,
  ) async {
    try {
      final result = await _sendReply(api, scope, text);
      if (!result.ok) {
        _log('兜底消息发送失败: ${result.error ?? '未知错误'}');
      }
    } catch (e) {
      _log('兜底消息发送异常: $e');
    }
  }

  void _log(String message) {
    debugPrint('[Bot] $message');
    TerminalUtil.instance.appendBotLog(message);
  }

  static String _abbrev(String value, int max) {
    final oneLine = value.replaceAll(RegExp(r'\s+'), ' ').trim();
    return oneLine.length > max ? '${oneLine.substring(0, max)}…' : oneLine;
  }
}
