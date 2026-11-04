import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dart_agent_core/dart_agent_core.dart';
import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;

import '../terminal/terminal_util.dart';
import '../database_util.dart';
import 'bot_paths.dart';
import 'bot_llm.dart';
import 'bot_models.dart';
import 'bot_prompt_sanitizer.dart';
import 'bot_skill_service.dart';
import 'bot_voice_client.dart';
import 'bot_voice_config.dart';
import 'llbot_api_client.dart';

class BotToolRegistry {
  BotToolRegistry({
    required this.repository,
    required this.api,
    required this.skills,
  });

  final BotRepository repository;
  final LlbotApiClient api;
  final BotSkillService skills;

  static const _maxFileBytes = 262144;
  static const _maxImageBytes = 10 * 1024 * 1024;
  static const _maxSendFileBytes = 20 * 1024 * 1024;
  static const _maxDownloadBytes = 50 * 1024 * 1024;
  static const _maxWebBytes = 2 * 1024 * 1024;
  static const _shellTimeout = Duration(seconds: 15);
  static const _maxShellOutput = 4000;
  static const _ignoredDirs = {
    '.git',
    'node_modules',
    'target',
    'build',
    '.idea',
    'dist',
    '__pycache__',
  };

  static final _shellBlocked = <RegExp>[
    RegExp(r'\brm\s+(-[a-zA-Z]+\s+)*/(\s|$)', caseSensitive: false),
    RegExp(r'\bmkfs(\.|\s|$)', caseSensitive: false),
    RegExp(r'\b(shutdown|reboot|poweroff|halt)\b', caseSensitive: false),
    RegExp(r'\bdd\b[^\n]*\bof=/dev/', caseSensitive: false),
    RegExp(r':\(\)\s*\{'),
    RegExp(r'\bchmod\s+-R\s+777\s+/', caseSensitive: false),
    RegExp(r'\bchown\s+-R\s+[^\s]+\s+/\s*$', caseSensitive: false),
    RegExp(r'>\s*/dev/(sd|nvme|hd)', caseSensitive: false),
  ];

  late final List<Tool> tools = _buildTools();

  List<Tool> build() => tools;

  static const _proactiveToolNames = {
    'currentTime',
    'addTask',
    'addTaskStep',
    'getTaskProgress',
    'startTask',
    'pauseTask',
    'resumeTask',
    'completeTask',
    'completeStep',
    'deleteTask',
    'listSkills',
    'useSkill',
    'describeImage',
    'shareVideo',
    'BraveWebSearch',
    'webFetch',
    'listTools',
  };

  static const _executorToolNames = {
    'currentTime',
    'addTask',
    'addTaskStep',
    'getTaskProgress',
    'startTask',
    'pauseTask',
    'resumeTask',
    'completeTask',
    'completeStep',
    'deleteTask',
    'describeImage',
    'downloadFile',
    'readFile',
    'writeFile',
    'editFile',
    'listDirectory',
    'grep',
    'glob',
    'BraveWebSearch',
    'webFetch',
    'listTools',
  };

  List<Tool> buildForProactive() => tools
      .where((tool) => _proactiveToolNames.contains(tool.name))
      .toList(growable: false);

  List<Tool> buildForExecutor() => tools
      .where((tool) => _executorToolNames.contains(tool.name))
      .toList(growable: false);

  List<Tool> _buildTools() {
    return [
      _tool(
        name: 'currentTime',
        description: '获取当前北京时间，格式为 yyyy-MM-dd HH:mm:ss 星期几。',
        parameters: {'type': 'object', 'properties': {}},
        handler: (_) async {
          final now = DateTime.now();
          const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
          final weekday = weekdays[now.weekday - 1];
          return '${now.year}-${_two(now.month)}-${_two(now.day)} '
              '${_two(now.hour)}:${_two(now.minute)}:${_two(now.second)} 星期$weekday';
        },
      ),
      _tool(
        name: 'sendMessages',
        description: '向当前私聊用户或群聊发送一条或多条文本消息。',
        parameters: {
          'type': 'object',
          'properties': {
            'messages': {
              'type': 'array',
              'items': {'type': 'string'},
              'description': '要发送的文本消息列表。',
            },
          },
          'required': ['messages'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final raw = args['messages'];
          if (raw is! List) {
            throw ArgumentError('messages 必须是字符串数组');
          }
          final messages = raw
              .map((item) => item?.toString().trim() ?? '')
              .where((item) => item.isNotEmpty)
              .toList();
          if (messages.isEmpty) {
            return '没有可发送的消息';
          }
          if (messages.length > 10) {
            throw ArgumentError('一次最多发送 10 条消息');
          }
          if (scope.isGroup && scope.groupId == null) {
            throw StateError('缺少群号');
          }
          var sent = 0;
          for (var i = 0; i < messages.length; i++) {
            final text = messages[i];
            if (text.length > 2000) {
              throw ArgumentError('第 ${i + 1} 条消息过长（最多 2000 字）');
            }
            final result = scope.isGroup
                ? await api.sendGroup(scope.groupId!, text)
                : await api.sendPrivate(scope.userId, text);
            if (!result.ok) {
              return '已发送 $sent 条，第 ${i + 1} 条发送失败: '
                  '${result.error ?? '未知错误'}';
            }
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
            sent++;
            if (i < messages.length - 1) {
              await Future<void>.delayed(const Duration(milliseconds: 300));
            }
          }
          return '已依次发送 $sent 条消息';
        },
      ),
      _tool(
        name: 'readFile',
        description: '读取沙箱中的文本文件。相对路径默认相对当前 QQ 目录；skills/、tools/、memory/ 都是当前 QQ 的独立目录。',
        parameters: {
          'type': 'object',
          'properties': {
            'path': {'type': 'string', 'description': '文件路径。'},
            'offset': {
              'type': 'integer',
              'description': '起始行，从 0 开始，可选。',
            },
            'limit': {
              'type': 'integer',
              'description': '最多读取行数，可选。',
            },
          },
          'required': ['path'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final path = await _resolvePath(
            args['path']?.toString() ?? '',
            scope.userId,
          );
          final file = File(path);
          if (!await file.exists()) {
            throw ArgumentError('文件不存在: $path');
          }
          final length = await file.length();
          if (length > _maxFileBytes) {
            throw ArgumentError('文件超过 ${_maxFileBytes ~/ 1024}KB，拒绝读取');
          }
          final content = await file.readAsString();
          final offset = _intArg(args['offset']) ?? 0;
          final limit = _intArg(args['limit']);
          if (offset == 0 && limit == null) {
            return content;
          }
          final lines = content.split('\n');
          final start = offset.clamp(0, lines.length);
          final end = limit == null
              ? lines.length
              : (start + limit).clamp(start, lines.length);
          return lines.sublist(start, end).join('\n');
        },
      ),
      _tool(
        name: 'writeFile',
        description: '写入沙箱中的文本文件，必要时自动创建目录。相对路径默认相对当前用户目录。',
        parameters: {
          'type': 'object',
          'properties': {
            'path': {'type': 'string', 'description': '文件路径。'},
            'content': {'type': 'string', 'description': '文件内容。'},
          },
          'required': ['path', 'content'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final path = await _resolvePath(
            args['path']?.toString() ?? '',
            scope.userId,
            forWrite: true,
          );
          final content = args['content']?.toString() ?? '';
          await File(path).writeAsString(content, flush: true);
          return '已写入 $path';
        },
      ),
      _tool(
        name: 'editFile',
        description: '替换沙箱文本文件中的字符串。默认只替换第一处，replaceAll=true 时替换全部。',
        parameters: {
          'type': 'object',
          'properties': {
            'path': {'type': 'string', 'description': '文件路径。'},
            'oldString': {'type': 'string', 'description': '要查找的原文。'},
            'newString': {'type': 'string', 'description': '替换后的内容。'},
            'replaceAll': {
              'type': 'boolean',
              'description': '是否替换全部，默认 false。',
            },
          },
          'required': ['path', 'oldString', 'newString'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final path = await _resolvePath(
            args['path']?.toString() ?? '',
            scope.userId,
            forWrite: true,
          );
          final file = File(path);
          if (!await file.exists()) {
            throw ArgumentError('文件不存在: $path');
          }
          final content = await file.readAsString();
          final oldString = args['oldString']?.toString() ?? '';
          final newString = args['newString']?.toString() ?? '';
          if (oldString.isEmpty) {
            throw ArgumentError('oldString 不能为空');
          }
          if (!content.contains(oldString)) {
            throw ArgumentError('文件中没有找到要替换的内容');
          }
          final replaceAll = args['replaceAll'] == true;
          final updated = replaceAll
              ? content.replaceAll(oldString, newString)
              : content.replaceFirst(oldString, newString);
          await file.writeAsString(updated, flush: true);
          return '已更新 $path';
        },
      ),
      _tool(
        name: 'listDirectory',
        description: '列出沙箱目录内容。相对路径默认相对当前用户目录。',
        parameters: {
          'type': 'object',
          'properties': {
            'path': {
              'type': 'string',
              'description': '目录路径，默认 .',
            },
            'depth': {
              'type': 'integer',
              'description': '递归深度，默认 1，最大 4。',
            },
            'limit': {
              'type': 'integer',
              'description': '最多返回条数，默认 200，最大 500。',
            },
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final path = await _resolvePath(
            (args['path']?.toString().trim().isNotEmpty ?? false)
                ? args['path'].toString()
                : '.',
            scope.userId,
          );
          final root = Directory(path);
          if (!await root.exists()) {
            throw ArgumentError('目录不存在: $path');
          }
          final maxDepth = (_intArg(args['depth']) ?? 1).clamp(1, 4);
          final maxLines = (_intArg(args['limit']) ?? 200).clamp(1, 500);
          final lines = <String>[];
          await for (final entity in root.list(recursive: true)) {
            final relative = p.relative(entity.path, from: root.path);
            final entityDepth = p.split(relative).length;
            if (entityDepth > maxDepth) {
              continue;
            }
            if (entity is Directory) {
              lines.add('[目录] $relative/');
            } else if (entity is File) {
              lines.add('[文件] $relative (${await entity.length()} B)');
            }
            if (lines.length >= maxLines) {
              lines.add('…（已截断，最多 $maxLines 条）');
              break;
            }
          }
          lines.sort();
          return lines.isEmpty ? '目录为空' : lines.join('\n');
        },
      ),
      _tool(
        name: 'grep',
        description: '在当前 QQ 目录（或 skills/、tools/、memory/）的文本文件里用正则搜索内容，返回文件、行号和内容。',
        parameters: {
          'type': 'object',
          'properties': {
            'pattern': {'type': 'string', 'description': '正则表达式。'},
            'path': {'type': 'string', 'description': '搜索目录，默认当前 QQ 根目录。'},
            'glob': {'type': 'string', 'description': '文件名 glob 过滤，如 *.md，可选。'},
          },
          'required': ['pattern'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final pattern = args['pattern']?.toString() ?? '';
          if (pattern.trim().isEmpty) {
            throw ArgumentError('pattern 不能为空');
          }
          RegExp regex;
          try {
            regex = RegExp(pattern);
          } catch (_) {
            throw ArgumentError('正则表达式无效');
          }
          final target = await _resolvePath(
            (args['path']?.toString().trim().isNotEmpty ?? false)
                ? args['path'].toString()
                : '.',
            scope.userId,
          );
          final base = FileSystemEntity.isFileSync(target)
              ? File(target).parent.path
              : target;
          final files = <File>[];
          if (FileSystemEntity.isFileSync(target)) {
            files.add(File(target));
          } else {
            await for (final entity in Directory(target).list(recursive: true)) {
              if (entity is! File || _isIgnoredPath(entity.path)) {
                continue;
              }
              files.add(entity);
              if (files.length >= 500) {
                break;
              }
            }
          }
          final glob = args['glob']?.toString().trim() ?? '';
          final buffer = StringBuffer();
          var matches = 0;
          for (final file in files) {
            if (glob.isNotEmpty && !_matchesGlob(p.basename(file.path), glob)) {
              continue;
            }
            try {
              if (await file.length() > 512 * 1024) {
                continue;
              }
              final lines = await file.readAsLines();
              for (var i = 0; i < lines.length; i++) {
                if (!regex.hasMatch(lines[i])) {
                  continue;
                }
                matches++;
                final line = lines[i].trim();
                final short = line.length > 300 ? '${line.substring(0, 300)}…' : line;
                buffer.writeln(
                  '${p.relative(file.path, from: base).replaceAll('\\', '/')}:${i + 1}: $short',
                );
                if (matches >= 100 || buffer.length >= 6000) {
                  buffer.writeln('... (结果已截断)');
                  return buffer.toString().trimRight();
                }
              }
            } catch (_) {
              continue;
            }
          }
          return matches == 0 ? '没有匹配结果' : buffer.toString().trimRight();
        },
      ),
      _tool(
        name: 'glob',
        description: '按 glob 模式查找文件，如 **/*.png。默认在当前 QQ 目录中查找，最多返回 200 条。',
        parameters: {
          'type': 'object',
          'properties': {
            'pattern': {'type': 'string', 'description': 'glob 模式。'},
            'path': {'type': 'string', 'description': '搜索目录，默认当前 QQ 根目录。'},
          },
          'required': ['pattern'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final pattern = args['pattern']?.toString().trim() ?? '';
          if (pattern.isEmpty) {
            throw ArgumentError('pattern 不能为空');
          }
          final target = await _resolvePath(
            (args['path']?.toString().trim().isNotEmpty ?? false)
                ? args['path'].toString()
                : '.',
            scope.userId,
          );
          if (!FileSystemEntity.isDirectorySync(target)) {
            throw ArgumentError('不是目录: $target');
          }
          final matcher = _globToRegExp(pattern);
          final results = <String>[];
          await for (final entity in Directory(target).list(recursive: true)) {
            if (entity is! File || _isIgnoredPath(entity.path)) {
              continue;
            }
            final relative =
                p.relative(entity.path, from: target).replaceAll('\\', '/');
            if (matcher.hasMatch(relative) ||
                matcher.hasMatch(p.basename(entity.path))) {
              results.add(relative);
              if (results.length >= 200) {
                break;
              }
            }
          }
          return results.isEmpty ? '没有匹配的文件' : results.join('\n');
        },
      ),
      _tool(
        name: 'listSkills',
        description: '列出当前可用的技能名称和描述。',
        parameters: {'type': 'object', 'properties': {}},
        handler: (_) async {
          final scope = _currentScope();
          final items = await skills.list(scope.userId);
          if (items.isEmpty) {
            return '暂无技能';
          }
          return items.map((item) => '- ${item.name}：${item.description}').join('\n');
        },
      ),
      _tool(
        name: 'useSkill',
        description: '加载指定技能的完整说明和步骤。',
        parameters: {
          'type': 'object',
          'properties': {
            'name': {'type': 'string', 'description': '技能名称。'},
          },
          'required': ['name'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final name = args['name']?.toString() ?? '';
          final skill = await skills.read(scope.userId, name);
          if (skill == null) {
            throw ArgumentError('技能不存在: $name');
          }
          return '# 技能：${skill.name}\n\n${skill.description}\n\n${skill.content}';
        },
      ),
      _tool(
        name: 'registerSkill',
        description: '创建或覆盖一个 Markdown 技能，注册后立即生效。',
        parameters: {
          'type': 'object',
          'properties': {
            'name': {'type': 'string', 'description': '技能名称。'},
            'description': {'type': 'string', 'description': '一句话描述。'},
            'content': {'type': 'string', 'description': '完整技能内容。'},
          },
          'required': ['name', 'description', 'content'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final skill = await skills.register(
            userId: scope.userId,
            name: args['name']?.toString() ?? '',
            description: args['description']?.toString() ?? '',
            content: args['content']?.toString() ?? '',
          );
          return '技能 ${skill.name} 已保存';
        },
      ),
      _tool(
        name: 'rememberPrivate',
        description: '记录关于当前私聊用户的长期记忆，仅在私聊中可见。',
        parameters: {
          'type': 'object',
          'properties': {
            'content': {'type': 'string', 'description': '记忆内容。'},
            'importance': {
              'type': 'integer',
              'description': '重要程度 1-5，默认 3。',
            },
          },
          'required': ['content'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final saved = await repository.rememberPrivate(
            scope: scope,
            content: args['content']?.toString() ?? '',
            importance: _intArg(args['importance']) ?? 3,
          );
          return saved ? '已记录私有记忆' : '这条记忆已经存在';
        },
      ),
      _tool(
        name: 'rememberShared',
        description: '记录群公共记忆或关系记忆，只能在群聊中使用。',
        parameters: {
          'type': 'object',
          'properties': {
            'content': {'type': 'string', 'description': '记忆内容。'},
            'participants': {
              'type': 'array',
              'items': {'type': 'integer'},
              'description': '关系记忆涉及的 QQ 号，可选。',
            },
          },
          'required': ['content'],
        },
        handler: (args) async {
          final scope = _currentScope();
          if (!scope.isGroup) {
            throw ArgumentError('公共记忆只能在群聊中记录');
          }
          final participants = (args['participants'] is List)
              ? (args['participants'] as List)
                  .map(_intArg)
                  .whereType<int>()
                  .toList()
              : const <int>[];
          final saved = await repository.rememberShared(
            scope: scope,
            content: args['content']?.toString() ?? '',
            participants: participants,
          );
          return saved ? '已记录公共记忆' : '这条记忆已经存在';
        },
      ),
      _tool(
        name: 'recallMemory',
        description: '按关键词回忆当前会话可见的长期记忆。',
        parameters: {
          'type': 'object',
          'properties': {
            'keyword': {'type': 'string', 'description': '关键词，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          return repository.recallText(scope, args['keyword']?.toString());
        },
      ),
      _tool(
        name: 'sendImage',
        description: '向当前私聊或群聊发送图片。支持 http(s) URL、沙箱路径或 base64://。',
        parameters: {
          'type': 'object',
          'properties': {
            'image': {'type': 'string', 'description': '图片地址或路径。'},
          },
          'required': ['image'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final image = args['image']?.toString().trim() ?? '';
          if (image.isEmpty) {
            throw ArgumentError('image 不能为空');
          }
          final remote =
              image.startsWith('http://') || image.startsWith('https://');
          final base64Source = image.startsWith('base64://');
          var sendSource = image;
          final dataUrl = await _loadImageDataUrl(image, scope.userId);
          if (!remote && !base64Source) {
            if (dataUrl == null) {
              throw ArgumentError('图片不存在、过大或无法读取: $image');
            }
            sendSource = 'base64://${dataUrl.substring(dataUrl.indexOf(',') + 1)}';
          }
          final result = await api.sendImage(
            userId: scope.userId,
            groupId: scope.isGroup ? scope.groupId : null,
            image: sendSource,
          );
          if (!result.ok) {
            return '图片发送失败: ${result.error ?? '未知错误'}';
          }
          final imageId = dataUrl == null
              ? null
              : await repository.storeImage(
                  scope: scope,
                  source: image,
                  dataUrl: dataUrl,
                );
          final content = '[图片] ${_abbrev(image, 200)}';
          await repository.appendConversation(
            scope: scope,
            role: 'bot',
            content: content,
            onebotMessageId: result.messageId,
          );
          await repository.appendContext(
            scope: scope,
            role: 'assistant',
            content: content,
            imageRefs: [if (imageId != null) 'image:$imageId' else image],
          );
          return '图片已发送';
        },
      ),
      _tool(
        name: 'sendFile',
        description: '向当前聊天发送文件。file 支持沙箱相对路径、http(s) 链接或 base64://；name 为可选显示名。',
        parameters: {
          'type': 'object',
          'properties': {
            'file': {'type': 'string', 'description': '文件路径、URL 或 base64://。'},
            'name': {'type': 'string', 'description': '显示文件名，可选。'},
          },
          'required': ['file'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final source = args['file']?.toString().trim() ?? '';
          if (source.isEmpty) {
            throw ArgumentError('file 不能为空');
          }
          if (scope.isGroup && scope.groupId == null) {
            throw StateError('缺少群号');
          }
          final remote =
              source.startsWith('http://') || source.startsWith('https://');
          final base64Source = source.startsWith('base64://');
          var uploadSource = source;
          if (!remote && !base64Source) {
            final path = await _resolvePath(source, scope.userId);
            final file = File(path);
            if (!await file.exists()) {
              throw ArgumentError('文件不存在: $source');
            }
            final length = await file.length();
            if (length == 0 || length > _maxSendFileBytes) {
              throw ArgumentError('文件为空或超过 ${_maxSendFileBytes ~/ 1024 ~/ 1024}MB');
            }
            uploadSource = 'base64://${base64Encode(await file.readAsBytes())}';
          }
          final name = _resolveFileName(
            args['name']?.toString(),
            source,
            fallback: 'file-${DateTime.now().millisecondsSinceEpoch}',
          );
          final fileId = scope.isGroup
              ? await api.uploadGroupFile(
                  groupId: scope.groupId!,
                  file: uploadSource,
                  name: name,
                )
              : await api.uploadPrivateFile(
                  userId: scope.userId,
                  file: uploadSource,
                  name: name,
                );
          if (fileId == null) {
            return '文件发送失败';
          }
          final content = '[文件] $name';
          await repository.appendConversation(
            scope: scope,
            role: 'bot',
            content: content,
          );
          await repository.appendContext(
            scope: scope,
            role: 'assistant',
            content: content,
          );
          return '已发送文件「$name」（file_id: $fileId）';
        },
      ),
      _tool(
        name: 'sendFace',
        description: '发送一个 QQ 系统表情（小黄脸）。常用 ID：0=惊讶，1=撇嘴，2=色，3=发呆，4=得意，5=流泪，6=害羞，7=闭嘴，8=睡，9=大哭，10=尴尬，11=发怒，12=调皮，13=呲牙，14=微笑，15=难过。',
        parameters: {
          'type': 'object',
          'properties': {
            'faceId': {'type': 'integer', 'description': '表情 ID。'},
          },
          'required': ['faceId'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final faceId = _intArg(args['faceId']);
          if (faceId == null || faceId < 0) {
            throw ArgumentError('faceId 不合法');
          }
          final result = await api.sendFace(
            userId: scope.userId,
            groupId: scope.isGroup ? scope.groupId : null,
            faceId: faceId,
          );
          if (!result.ok) {
            return '表情发送失败: ${result.error ?? '未知错误'}';
          }
          final content = '[表情 $faceId]';
          await repository.appendConversation(
            scope: scope,
            role: 'bot',
            content: content,
            onebotMessageId: result.messageId,
          );
          await repository.appendContext(
            scope: scope,
            role: 'assistant',
            content: content,
          );
          return '已发送表情 $faceId';
        },
      ),
      _tool(
        name: 'getFavoriteFaces',
        description: '获取当前 QQ 账号收藏的表情包图片链接，可挑选后用 sendImage 发送。',
        parameters: {'type': 'object', 'properties': {}},
        handler: (_) async {
          final urls = await api.fetchCustomFaces();
          if (urls.isEmpty) {
            return '没有获取到收藏表情';
          }
          final buffer = StringBuffer('收藏表情链接：');
          final limit = urls.length > 30 ? 30 : urls.length;
          for (var i = 0; i < limit; i++) {
            buffer.writeln('\n${i + 1}. ${urls[i]}');
          }
          if (urls.length > limit) {
            buffer.write('\n... 共 ${urls.length} 个，已省略剩余部分');
          }
          return buffer.toString().trimRight();
        },
      ),
      _tool(
        name: 'describeImage',
        description: '查看之前发送过的图片内容。imageId 省略时查看最近一张图片；返回文字描述。',
        parameters: {
          'type': 'object',
          'properties': {
            'imageId': {'type': 'integer', 'description': '图片编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final imageId = _intArg(args['imageId']);
          final row = await repository.findImage(scope, imageId: imageId);
          if (row == null) {
            return imageId == null ? '当前没有存储的图片' : '没有找到图片 #$imageId';
          }
          final dataUrl = row['data_url']?.toString() ?? '';
          final comma = dataUrl.indexOf(',');
          if (comma <= 0) {
            return '图片数据损坏';
          }
          final header = dataUrl.substring(0, comma);
          final match = RegExp(r'data:([^;]+);base64').firstMatch(header);
          final description = await BotLlm.completeWithImage(
            system: '你是一个图片描述助手，请客观、简洁地描述图片的主要内容。',
            user: '请描述这张图片的主要内容。',
            imageBase64: dataUrl.substring(comma + 1),
            mimeType: match?.group(1) ?? 'image/jpeg',
          );
          if (description.trim().isEmpty) {
            return '图片描述失败';
          }
          return '图片 #${row['id']}（来源：${row['source']}）：\n${description.trim()}';
        },
      ),
      _tool(
        name: 'downloadFile',
        description: '从网络下载文件并保存到 downloads 目录。url 必须是 http/https 公网地址。',
        parameters: {
          'type': 'object',
          'properties': {
            'url': {'type': 'string', 'description': '下载地址。'},
            'fileName': {'type': 'string', 'description': '保存文件名，可选。'},
          },
          'required': ['url'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final url = args['url']?.toString().trim() ?? '';
          if (url.isEmpty) {
            throw ArgumentError('url 不能为空');
          }
          final result = await _downloadRemoteFile(
            url,
            userId: scope.userId,
            fileName: args['fileName']?.toString(),
          );
          return '已下载到 ${result.$1}（${result.$2} 字节）';
        },
      ),
      _tool(
        name: 'sendVoice',
        description: '把文本合成为语音并发送到当前聊天。可传语音指令描述语气、情绪或语速。',
        parameters: {
          'type': 'object',
          'properties': {
            'text': {'type': 'string', 'description': '要合成并发送的文本。'},
            'contextTexts': {
              'type': 'array',
              'items': {'type': 'string'},
              'description': '语音指令列表，如“用温柔撒娇的语气说”，可选。',
            },
          },
          'required': ['text'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final voiceConfig = await BotVoiceConfig.load();
          if (!voiceConfig.enabled) {
            return '语音发送功能已关闭';
          }
          final text = args['text']?.toString().trim() ?? '';
          if (text.isEmpty) {
            throw ArgumentError('text 不能为空');
          }
          final instructions = args['contextTexts'] is List
              ? (args['contextTexts'] as List)
                  .map((value) => value?.toString() ?? '')
                  .toList()
              : const <String>[];
          final voice = await BotVoiceClient.load();
          final audio = await voice.synthesize(
            text,
            instructions: instructions,
          );
          final result = await api.sendVoice(
            userId: scope.userId,
            groupId: scope.isGroup ? scope.groupId : null,
            audio: audio,
          );
          if (!result.ok) {
            return '语音发送失败: ${result.error ?? '未知错误'}';
          }
          final content = '[语音] ${text.length > 500 ? '${text.substring(0, 500)}…' : text}';
          await repository.appendConversation(
            scope: scope,
            role: 'bot',
            content: content,
            onebotMessageId: result.messageId,
          );
          await repository.appendContext(
            scope: scope,
            role: 'assistant',
            content: content,
          );
          return '已发送语音（${text.length} 字）';
        },
      ),
      _tool(
        name: 'addTask',
        description: '创建一个多步骤任务。创建后用 startTask 让它后台执行。',
        parameters: {
          'type': 'object',
          'properties': {
            'title': {'type': 'string', 'description': '任务标题。'},
          },
          'required': ['title'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final title = args['title']?.toString().trim() ?? '';
          if (title.isEmpty) {
            throw ArgumentError('任务标题不能为空');
          }
          final id = await repository.addTask(scope, title);
          return '任务 #$id「$title」已创建，下一步用 addTaskStep 添加步骤';
        },
      ),
      _tool(
        name: 'addTaskStep',
        description: '给任务添加一个步骤。taskId 省略时使用当前会话最新未完成任务。',
        parameters: {
          'type': 'object',
          'properties': {
            'step': {'type': 'string', 'description': '步骤内容。'},
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
          'required': ['step'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          final step = args['step']?.toString().trim() ?? '';
          if (step.isEmpty) {
            throw ArgumentError('步骤内容不能为空');
          }
          await repository.addTaskStep(taskId, step);
          return await _taskProgress(taskId);
        },
      ),
      _tool(
        name: 'getTaskProgress',
        description: '查看任务及步骤进度。taskId 省略时查看最新未完成任务。',
        parameters: {
          'type': 'object',
          'properties': {
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          return _taskProgress(taskId);
        },
      ),
      _tool(
        name: 'startTask',
        description: '开始执行任务，后台执行器会逐步推进。',
        parameters: {
          'type': 'object',
          'properties': {
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          await repository.updateTaskStatus(taskId, 'IN_PROGRESS');
          return '任务 #$taskId 已开始执行';
        },
      ),
      _tool(
        name: 'pauseTask',
        description: '暂停任务。',
        parameters: {
          'type': 'object',
          'properties': {
            'reason': {'type': 'string', 'description': '暂停原因。'},
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
          'required': ['reason'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          await repository.updateTaskStatus(
            taskId,
            'PAUSED',
            pauseReason: args['reason']?.toString(),
          );
          return '任务 #$taskId 已暂停';
        },
      ),
      _tool(
        name: 'resumeTask',
        description: '恢复一个已暂停的任务。',
        parameters: {
          'type': 'object',
          'properties': {
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          await repository.updateTaskStatus(taskId, 'IN_PROGRESS');
          return '任务 #$taskId 已恢复';
        },
      ),
      _tool(
        name: 'completeTask',
        description: '把任务标记为完成。',
        parameters: {
          'type': 'object',
          'properties': {
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          await repository.completeTask(taskId);
          return '任务 #$taskId 已完成';
        },
      ),
      _tool(
        name: 'completeStep',
        description: '完成任务的某一个步骤。stepNo 省略时完成第一个待办步骤。',
        parameters: {
          'type': 'object',
          'properties': {
            'stepNo': {'type': 'integer', 'description': '步骤编号，可选。'},
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          final steps = await repository.taskSteps(taskId);
          final stepNo = _intArg(args['stepNo']);
          Map<String, Object?>? target;
          if (stepNo != null) {
            for (final step in steps) {
              if (step['step_no'] == stepNo) {
                target = step;
                break;
              }
            }
          } else {
            for (final step in steps) {
              if (step['status'] == 'PENDING') {
                target = step;
                break;
              }
            }
          }
          if (target == null) {
            throw StateError('没有找到可完成的步骤');
          }
          await repository.updateStepStatus(
            target['id'] as int,
            'COMPLETED',
          );
          final task = await repository.getTask(taskId);
          if (task != null && task['status'] == 'PENDING') {
            await repository.updateTaskStatus(taskId, 'IN_PROGRESS');
          }
          final after = await repository.taskSteps(taskId);
          if (after.every((step) => step['status'] == 'COMPLETED')) {
            await repository.updateTaskStatus(taskId, 'COMPLETED');
          }
          return '步骤 ${target['step_no']} 已完成';
        },
      ),
      _tool(
        name: 'deleteTask',
        description: '删除任务及其全部步骤。',
        parameters: {
          'type': 'object',
          'properties': {
            'taskId': {'type': 'integer', 'description': '任务编号，可选。'},
          },
        },
        handler: (args) async {
          final scope = _currentScope();
          final taskId = await _resolveTaskId(args, scope);
          await repository.deleteTask(taskId);
          return '任务 #$taskId 已删除';
        },
      ),
      _tool(
        name: 'scheduleReminder',
        description: '设置定时提醒。到点后会把预设提示词交给 AI 执行并回复。',
        parameters: {
          'type': 'object',
          'properties': {
            'remindAt': {
              'type': 'string',
              'description': '提醒时间，如 2026-11-05 09:30 或 ISO8601。',
            },
            'message': {
              'type': 'string',
              'description': '到点后交给 AI 的提示词。',
            },
          },
          'required': ['remindAt', 'message'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final time = _parseDateTime(args['remindAt']);
          if (time == null) {
            throw FormatException('无法解析提醒时间: ${args['remindAt']}');
          }
          if (!time.isAfter(DateTime.now())) {
            throw ArgumentError('提醒时间已经过去，请设置一个将来的时间');
          }
          final message = args['message']?.toString().trim() ?? '';
          if (message.isEmpty) {
            throw ArgumentError('提醒内容不能为空');
          }
          if (message.length > 1000) {
            throw ArgumentError('提醒内容过长（最多 1000 字）');
          }
          final id = await repository.addReminder(
            scope,
            message,
            time.millisecondsSinceEpoch,
          );
          return '提醒 #$id 已设置：${time.toString().substring(0, 16)}';
        },
      ),
      _tool(
        name: 'shareVideo',
        description: '把一个视频加入当前会话的视频分享队列。',
        parameters: {
          'type': 'object',
          'properties': {
            'url': {'type': 'string', 'description': '视频链接。'},
            'title': {'type': 'string', 'description': '视频标题，可选。'},
            'comment': {'type': 'string', 'description': '推荐语，可选。'},
          },
          'required': ['url'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final url = args['url']?.toString().trim() ?? '';
          if (url.isEmpty) {
            throw ArgumentError('url 不能为空');
          }
          if (!url.startsWith('http://') && !url.startsWith('https://')) {
            throw ArgumentError('视频链接必须是 http/https');
          }
          final id = await repository.enqueueVideo(
            scope,
            url,
            title: args['title']?.toString(),
            comment: args['comment']?.toString(),
          );
          final count = await repository.pendingVideoCountForScope(scope);
          return id < 0
              ? '这个视频已经在队列中，当前待发送 $count 条'
              : '已加入视频队列，当前待发送 $count 条';
        },
      ),
      _tool(
        name: 'videoQueueStatus',
        description: '查询视频队列中还有多少条待发送。',
        parameters: {'type': 'object', 'properties': {}},
        handler: (_) async {
          final scope = _currentScope();
          final mine = await repository.pendingVideoCountForScope(scope);
          final all = await repository.pendingVideoCount();
          final next = await repository.nextPendingVideoForScope(scope);
          final title = next?['title']?.toString().trim() ?? '';
          final buffer = StringBuffer('你还有 $mine 个视频没看');
          if (title.isNotEmpty) {
            buffer.write('，下一个是《$title》');
          }
          buffer.write('；队列中共有 $all 个待发送视频。');
          return buffer.toString();
        },
      ),
      _tool(
        name: 'nextVideo',
        description: '立即发送视频队列中的下一个视频。',
        parameters: {'type': 'object', 'properties': {}},
        handler: (_) async {
          final scope = _currentScope();
          if (scope.isGroup && scope.groupId == null) {
            return '缺少群号';
          }
          final video = await repository.nextPendingVideoForScope(scope);
          if (video == null) {
            return '队列里没有待发送的视频';
          }
          final videoId = video['id'] as int?;
          if (videoId == null) {
            return '视频数据异常';
          }
          if (!await repository.claimVideo(
            videoId,
            video['attempts'] as int? ?? 0,
          )) {
            return '队列正在处理，请稍后再试';
          }
          final text = [
            '看到一个有意思的视频，分享给你～',
            if ((video['title']?.toString().trim() ?? '').isNotEmpty)
              video['title']!.toString().trim(),
            video['url']?.toString() ?? '',
            if ((video['comment']?.toString().trim() ?? '').isNotEmpty)
              video['comment']!.toString().trim(),
          ].join('\n');
          final result = scope.isGroup
              ? await api.sendGroup(scope.groupId!, text)
              : await api.sendPrivate(scope.userId, text);
          if (!result.ok) {
            await repository.markVideoFailed(videoId);
            return '视频发送失败，请稍后再试';
          }
          await repository.markVideoSent(videoId, messageId: result.messageId);
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
          return '已发送视频${(video['title']?.toString().trim() ?? '').isEmpty ? '' : '《${video['title']}》'}';
        },
      ),
      _tool(
        name: 'runShell',
        description: '在当前 QQ 工作目录执行一条 shell 命令，返回输出和退出码。有超时和输出长度限制；仅在确实需要系统命令时使用。',
        parameters: {
          'type': 'object',
          'properties': {
            'command': {'type': 'string', 'description': '要执行的命令。'},
          },
          'required': ['command'],
        },
        handler: (args) async {
          final scope = _currentScope();
          final command = args['command']?.toString() ?? '';
          if (command.trim().isEmpty) {
            throw ArgumentError('命令不能为空');
          }
          if (command.length > 2000) {
            throw ArgumentError('命令过长');
          }
          final enabled =
              (await DatabaseUtil.instance.getSetting('shell_enabled') ?? '1') !=
                  '0';
          if (!enabled) {
            return 'shell 工具已禁用';
          }
          for (final pattern in _shellBlocked) {
            if (pattern.hasMatch(command)) {
              return '该命令被安全策略拒绝';
            }
          }
          await BotPaths.ensureUserDirs(scope.userId);
          final workingDir = BotPaths.userRoot(scope.userId).path;
          final process = Platform.isWindows
              ? await Process.start(
                  'cmd.exe',
                  ['/c', command],
                  workingDirectory: workingDir,
                )
              : await Process.start(
                  '/bin/sh',
                  ['-c', command],
                  workingDirectory: workingDir,
                );
          final outFuture = process.stdout
              .transform(const Utf8Decoder(allowMalformed: true))
              .join();
          final errFuture = process.stderr
              .transform(const Utf8Decoder(allowMalformed: true))
              .join();
          int exitCode;
          try {
            exitCode = await process.exitCode.timeout(_shellTimeout);
          } on TimeoutException {
            process.kill();
            exitCode = -1;
          }
          final stdout = await outFuture;
          final stderr = await errFuture;
          final output = [stdout, stderr]
              .where((value) => value.trim().isNotEmpty)
              .join('\n')
              .trim();
          final text = BotPromptSanitizer.truncate(output, _maxShellOutput);
          return '退出码 $exitCode\n$text';
        },
      ),
      _tool(
        name: 'BraveWebSearch',
        description: '使用 Brave 搜索网页，返回标题、链接和摘要。需要最新信息或不确定答案时使用。',
        parameters: {
          'type': 'object',
          'properties': {
            'query': {'type': 'string', 'description': '搜索关键词。'},
          },
          'required': ['query'],
        },
        handler: (args) async {
          final query = args['query']?.toString().trim() ?? '';
          if (query.isEmpty) {
            throw ArgumentError('搜索关键词不能为空');
          }
          final key =
              (await DatabaseUtil.instance.getSecretValue('brave_key'))?.trim() ??
                  '';
          if (key.isEmpty) {
            return '错误：未配置 Brave Search API Key';
          }
          try {
            final response = await Dio(
              BaseOptions(
                connectTimeout: const Duration(seconds: 10),
                receiveTimeout: const Duration(seconds: 25),
              ),
            ).get<dynamic>(
              'https://api.search.brave.com/res/v1/web/search',
              queryParameters: {'q': query, 'count': 5},
              options: Options(
                headers: {
                  'Accept': 'application/json',
                  'X-Subscription-Token': key,
                },
              ),
            );
            if (response.statusCode != 200) {
              return '搜索失败：HTTP ${response.statusCode}';
            }
            final data = response.data;
            final web = data is Map ? data['web'] : null;
            final results = web is Map ? web['results'] : null;
            if (results is! List) {
              return '没有找到与「$query」相关的结果';
            }
            final buffer = StringBuffer('搜索「$query」的结果：');
            var count = 0;
            for (final item in results) {
              if (item is! Map || count >= 5) {
                break;
              }
              final url = item['url']?.toString().trim() ?? '';
              if (url.isEmpty) {
                continue;
              }
              count++;
              final title = item['title']?.toString().trim() ?? url;
              final description =
                  (item['description']?.toString() ?? '').replaceAll(
                RegExp(r'\s+'),
                ' ',
              ).trim();
              buffer.writeln('\n$count. $title\n$url');
              if (description.isNotEmpty) {
                buffer.writeln(
                  description.length > 300
                      ? '${description.substring(0, 300)}…'
                      : description,
                );
              }
            }
            return count == 0
                ? '没有找到与「$query」相关的结果'
                : buffer.toString().trimRight();
          } catch (e) {
            return '搜索失败：$e';
          }
        },
      ),
      _tool(
        name: 'webFetch',
        description: '抓取一个网页并返回去除 HTML 标签后的正文，适合阅读文章、文档或页面内容。',
        parameters: {
          'type': 'object',
          'properties': {
            'url': {'type': 'string', 'description': '网页地址，必须 http/https。'},
          },
          'required': ['url'],
        },
        handler: (args) async {
          final url = args['url']?.toString().trim() ?? '';
          if (url.isEmpty) {
            throw ArgumentError('url 不能为空');
          }
          return _fetchWebText(url);
        },
      ),
      _tool(
        name: 'listTools',
        description: '列出当前 AI 可用的所有工具及其用途。',
        parameters: {'type': 'object', 'properties': {}},
        handler: (_) async {
          final lines = tools.map((tool) {
            final description = tool.description.trim().split('\n').first;
            return '- ${tool.name}：$description';
          });
          return lines.join('\n');
        },
      ),
    ];
  }

  Future<String?> _loadImageDataUrl(String source, int userId) async {
    try {
      List<int> bytes;
      String? contentType;
      if (source.startsWith('base64://')) {
        bytes = base64Decode(source.substring('base64://'.length));
      } else if (source.startsWith('http://') ||
          source.startsWith('https://')) {
        final response = await Dio(
          BaseOptions(
            connectTimeout: const Duration(seconds: 10),
            receiveTimeout: const Duration(seconds: 30),
          ),
        ).get<List<int>>(
          source,
          options: Options(
            responseType: ResponseType.bytes,
            followRedirects: true,
          ),
        );
        bytes = response.data ?? const [];
        contentType = response.headers.value(Headers.contentTypeHeader);
      } else {
        final path = await _resolvePath(source, userId);
        final file = File(path);
        if (!await file.exists()) {
          return null;
        }
        bytes = await file.readAsBytes();
      }
      if (bytes.isEmpty || bytes.length > _maxImageBytes) {
        return null;
      }
      final mime = _detectImageMime(bytes, contentType);
      return 'data:$mime;base64,${base64Encode(bytes)}';
    } catch (_) {
      return null;
    }
  }

  static String _detectImageMime(List<int> bytes, String? contentType) {
    final type = contentType?.split(';').first.trim().toLowerCase();
    if (type != null && type.startsWith('image/')) {
      return type;
    }
    if (bytes.length >= 3 &&
        bytes[0] == 0xFF &&
        bytes[1] == 0xD8 &&
        bytes[2] == 0xFF) {
      return 'image/jpeg';
    }
    if (bytes.length >= 8 &&
        bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return 'image/png';
    }
    if (bytes.length >= 6 &&
        bytes[0] == 0x47 &&
        bytes[1] == 0x49 &&
        bytes[2] == 0x46) {
      return 'image/gif';
    }
    if (bytes.length >= 12 &&
        bytes[8] == 0x57 &&
        bytes[9] == 0x45 &&
        bytes[10] == 0x42 &&
        bytes[11] == 0x50) {
      return 'image/webp';
    }
    return 'image/jpeg';
  }

  Future<(String, int)> _downloadRemoteFile(
    String url, {
    required int userId,
    String? fileName,
  }) async {
    final uri = Uri.tryParse(url.trim());
    if (uri == null) {
      throw ArgumentError('URL 不合法');
    }
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      final response = await _openRemote(client, uri, 3);
      if (response.statusCode != 200) {
        await response.drain();
        throw ArgumentError('下载失败，HTTP ${response.statusCode}');
      }
      final dir = BotPaths.userDownloadsDir(userId);
      await dir.create(recursive: true);
      final name = _downloadName(
        fileName,
        uri,
        response.headers.contentType?.mimeType,
        dir,
      );
      final file = File(p.join(dir.path, name));
      final sink = file.openWrite();
      var total = 0;
      try {
        await for (final chunk in response) {
          total += chunk.length;
          if (total > _maxDownloadBytes) {
            await sink.close();
            await file.delete();
            throw ArgumentError('文件超过大小上限');
          }
          sink.add(chunk);
        }
        await sink.close();
      } catch (_) {
        await sink.close();
        rethrow;
      }
      return ('downloads/$name', total);
    } finally {
      client.close(force: true);
    }
  }

  Future<HttpClientResponse> _openRemote(
    HttpClient client,
    Uri uri,
    int redirects,
  ) async {
    await _validateRemoteUri(uri);
    final request = await client.getUrl(uri);
    request.followRedirects = false;
    request.headers.set(HttpHeaders.userAgentHeader, 'HumanoidBot/1.0');
    final response = await request.close();
    final status = response.statusCode;
    if (status >= 300 && status < 400) {
      if (redirects <= 0) {
        await response.drain();
        throw ArgumentError('重定向次数过多');
      }
      final location = response.headers.value(HttpHeaders.locationHeader);
      await response.drain();
      if (location == null || location.isEmpty) {
        throw ArgumentError('重定向缺少 Location');
      }
      return _openRemote(client, uri.resolve(location), redirects - 1);
    }
    return response;
  }

  Future<void> _validateRemoteUri(Uri uri) async {
    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      throw ArgumentError('只支持 http/https 地址');
    }
    final host = uri.host;
    if (host.isEmpty) {
      throw ArgumentError('URL 缺少主机名');
    }
    final addresses = await InternetAddress.lookup(host);
    if (addresses.isEmpty) {
      throw ArgumentError('无法解析主机名');
    }
    for (final address in addresses) {
      if (_isBlockedAddress(address)) {
        throw ArgumentError('禁止访问内网或保留地址');
      }
    }
  }

  static bool _isBlockedAddress(InternetAddress address) {
    if (address.isLoopback || address.isLinkLocal || address.isMulticast) {
      return true;
    }
    final bytes = address.rawAddress;
    if (bytes.length == 4) {
      final a = bytes[0];
      final b = bytes[1];
      if (a == 0 || a == 255 || a == 10 || a == 127) {
        return true;
      }
      if (a == 169 && b == 254) {
        return true;
      }
      if (a == 172 && b >= 16 && b <= 31) {
        return true;
      }
      if (a == 192 && b == 168) {
        return true;
      }
      if (a == 100 && b >= 64 && b <= 127) {
        return true;
      }
      if (a >= 224) {
        return true;
      }
    }
    if (bytes.length == 16) {
      if ((bytes[0] & 0xFE) == 0xFC) {
        return true;
      }
      if (bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80) {
        return true;
      }
    }
    return false;
  }

  static String _downloadName(
    String? fileName,
    Uri uri,
    String? contentType,
    Directory dir,
  ) {
    var name = fileName?.trim() ?? '';
    if (name.isEmpty) {
      name = p.basename(uri.path);
    }
    name = name
        .replaceAll(RegExp(r'[\\/:*?"<>|]'), '_')
        .replaceAll(RegExp(r'[\x00-\x1F]'), '')
        .trim();
    if (name.isEmpty || name == '.' || name == '..') {
      name = 'download-${DateTime.now().millisecondsSinceEpoch}';
    }
    if (!name.contains('.')) {
      final extension = _extensionFor(contentType);
      if (extension.isNotEmpty) {
        name += extension;
      }
    }
    var target = p.join(dir.path, name);
    var index = 1;
    final base = name;
    while (File(target).existsSync()) {
      final dot = base.lastIndexOf('.');
      final candidate = dot > 0
          ? '${base.substring(0, dot)}-$index${base.substring(dot)}'
          : '$base-$index';
      target = p.join(dir.path, candidate);
      index++;
    }
    return p.basename(target);
  }

  static String _extensionFor(String? contentType) {
    final type = contentType?.toLowerCase() ?? '';
    if (type.contains('image/jpeg')) return '.jpg';
    if (type.contains('image/png')) return '.png';
    if (type.contains('image/gif')) return '.gif';
    if (type.contains('image/webp')) return '.webp';
    if (type.contains('application/pdf')) return '.pdf';
    if (type.contains('application/zip')) return '.zip';
    if (type.contains('text/plain')) return '.txt';
    if (type.contains('application/json')) return '.json';
    return '';
  }

  static String _resolveFileName(
    String? name,
    String source, {
    required String fallback,
  }) {
    var candidate = name?.trim() ?? '';
    if (candidate.isEmpty) {
      if (source.startsWith('http://') || source.startsWith('https://')) {
        try {
          candidate = p.basename(Uri.parse(source).path);
        } catch (_) {
          // Use fallback below.
        }
      } else if (!source.startsWith('base64://')) {
        candidate = p.basename(source.replaceAll('\\', '/'));
      }
    }
    candidate = candidate.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();
    return candidate.isEmpty ? fallback : candidate;
  }

  Future<String> _fetchWebText(String url) async {
    final uri = Uri.tryParse(url.trim());
    if (uri == null) {
      throw ArgumentError('URL 不合法');
    }
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      final response = await _openRemote(client, uri, 3);
      if (response.statusCode != 200) {
        await response.drain();
        throw ArgumentError('抓取失败，HTTP ${response.statusCode}');
      }
      final bytes = <int>[];
      await for (final chunk in response) {
        bytes.addAll(chunk);
        if (bytes.length > _maxWebBytes) {
          throw ArgumentError('网页内容过大');
        }
      }
      final contentType = response.headers.contentType?.mimeType ?? '';
      if (contentType.isNotEmpty &&
          !contentType.contains('text') &&
          !contentType.contains('html') &&
          !contentType.contains('json') &&
          !contentType.contains('xml')) {
        return '不支持的内容类型: $contentType';
      }
      final body = utf8.decode(bytes, allowMalformed: true);
      final title = RegExp(
        r'<title[^>]*>(.*?)</title>',
        dotAll: true,
        caseSensitive: false,
      ).firstMatch(body)?.group(1)?.trim() ??
          '';
      final text = _stripHtml(body);
      final output = title.isEmpty ? text : '标题：$title\n\n$text';
      return BotPromptSanitizer.truncate(output, 8000);
    } finally {
      client.close(force: true);
    }
  }

  static String _stripHtml(String html) {
    final text = html
        .replaceAll(
          RegExp(
            r'<script[^>]*>.*?</script>',
            dotAll: true,
            caseSensitive: false,
          ),
          ' ',
        )
        .replaceAll(
          RegExp(
            r'<style[^>]*>.*?</style>',
            dotAll: true,
            caseSensitive: false,
          ),
          ' ',
        )
        .replaceAll(RegExp(r'<[^>]+>'), ' ')
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll('&#39;', "'");
    return text.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  static bool _isIgnoredPath(String path) {
    for (final part in p.split(path)) {
      if (_ignoredDirs.contains(part)) {
        return true;
      }
    }
    return false;
  }

  static bool _matchesGlob(String name, String pattern) {
    return _globToRegExp(pattern).hasMatch(name);
  }

  static RegExp _globToRegExp(String glob) {
    final normalized = glob.replaceAll('\\', '/');
    final buffer = StringBuffer('^');
    for (var i = 0; i < normalized.length; i++) {
      final ch = normalized[i];
      if (ch == '*') {
        if (i + 1 < normalized.length && normalized[i + 1] == '*') {
          buffer.write('.*');
          i++;
          if (i + 1 < normalized.length && normalized[i + 1] == '/') {
            i++;
          }
        } else {
          buffer.write('[^/]*');
        }
      } else if (ch == '?') {
        buffer.write('[^/]');
      } else {
        buffer.write(RegExp.escape(ch));
      }
    }
    buffer.write(r'$');
    return RegExp(buffer.toString(), caseSensitive: false);
  }

  Future<int> _resolveTaskId(
    Map<String, dynamic> args,
    BotScope scope,
  ) async {
    final explicit = _intArg(args['taskId']);
    if (explicit != null && explicit > 0) {
      return explicit;
    }
    final latest = await repository.latestOpenTaskId(scope);
    if (latest == null) {
      throw StateError('当前没有未完成任务，请先调用 addTask');
    }
    return latest;
  }

  Future<String> _taskProgress(int taskId) async {
    final task = await repository.getTask(taskId);
    if (task == null) {
      throw ArgumentError('任务不存在: $taskId');
    }
    final steps = await repository.taskSteps(taskId);
    final buffer = StringBuffer(
      '任务 #$taskId「${task['title']}」状态=${task['status']}\n',
    );
    for (final step in steps) {
      final done = step['status'] == 'COMPLETED';
      buffer.writeln(
        '${done ? '[x]' : '[ ]'} ${step['step_no']}. ${step['content']}',
      );
    }
    return buffer.toString().trimRight();
  }

  static DateTime? _parseDateTime(Object? value) {
    if (value is num) {
      return DateTime.fromMillisecondsSinceEpoch(value.toInt());
    }
    final text = value?.toString().trim() ?? '';
    if (text.isEmpty) {
      return null;
    }
    final parsed = DateTime.tryParse(text);
    if (parsed != null) {
      return parsed;
    }
    final match = RegExp(
      r'^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?$',
    ).firstMatch(text);
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

  Tool _tool({
    required String name,
    required String description,
    required Map<String, dynamic> parameters,
    required Future<Object?> Function(Map<String, dynamic> args) handler,
  }) {
    return Tool(
      name: name,
      description: description,
      parameters: parameters,
      parameterMode: ToolParameterMode.object,
      executable: (Map<String, dynamic> args) async {
        final scope = BotScope.fromMetadata(
          AgentCallToolContext.current?.state.metadata,
        );
        final started = DateTime.now();
        var resultText = '';
        var isError = false;
        try {
          final result = await handler(args);
          resultText = result is String ? result : jsonEncode(result);
          resultText = BotPromptSanitizer.truncate(
            BotPromptSanitizer.clean(resultText),
            6000,
          );
          return resultText;
        } catch (e) {
          isError = true;
          resultText = '错误：$e';
          rethrow;
        } finally {
          if (scope != null) {
            unawaited(
              repository.logToolCall(
                scope: scope,
                name: name,
                arguments: jsonEncode(args),
                result: resultText,
                isError: isError,
              ),
            );
          }
          TerminalUtil.instance.appendBotLog(
            '工具 $name ${isError ? '失败' : '完成'} '
            '(${DateTime.now().difference(started).inMilliseconds}ms)',
          );
        }
      },
    );
  }

  BotScope _currentScope() {
    final scope = BotScope.fromMetadata(
      AgentCallToolContext.current?.state.metadata,
    );
    if (scope == null) {
      throw StateError('当前工具调用没有会话上下文');
    }
    return scope;
  }

  Future<String> _resolvePath(
    String input,
    int userId, {
    bool forWrite = false,
  }) async {
    final raw = input.trim();
    if (raw.isEmpty) {
      throw ArgumentError('路径不能为空');
    }
    await BotPaths.ensureUserDirs(userId);
    final userRoot = p.normalize(p.absolute(BotPaths.userRoot(userId).path));
    final normalizedInput = raw.replaceAll('\\', '/');
    String candidate;
    if (normalizedInput == 'skills' ||
        normalizedInput.startsWith('skills/')) {
      final sub = normalizedInput == 'skills'
          ? ''
          : normalizedInput.substring('skills/'.length);
      candidate = p.join(BotPaths.userSkillsDir(userId).path, sub);
    } else if (normalizedInput == 'tools' ||
        normalizedInput.startsWith('tools/')) {
      final sub = normalizedInput == 'tools'
          ? ''
          : normalizedInput.substring('tools/'.length);
      candidate = p.join(BotPaths.userToolsDir(userId).path, sub);
    } else if (normalizedInput == 'memory' ||
        normalizedInput.startsWith('memory/')) {
      final sub = normalizedInput == 'memory'
          ? ''
          : normalizedInput.substring('memory/'.length);
      candidate = p.join(BotPaths.userMemoryDir(userId).path, sub);
    } else if (p.isAbsolute(raw)) {
      candidate = raw;
    } else {
      candidate = p.join(userRoot, raw);
    }
    final normalized = p.normalize(p.absolute(candidate));
    if (normalized != userRoot && !p.isWithin(userRoot, normalized)) {
      throw ArgumentError('路径越界，拒绝访问: $input');
    }
    if (forWrite) {
      await Directory(p.dirname(normalized)).create(recursive: true);
    }
    return normalized;
  }

  static int? _intArg(Object? value) {
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.toInt();
    }
    return int.tryParse(value?.toString() ?? '');
  }

  static String _abbrev(String value, int max) {
    final singleLine = value.replaceAll(RegExp(r'\s+'), ' ').trim();
    return singleLine.length > max
        ? '${singleLine.substring(0, max)}…'
        : singleLine;
  }

  static String _two(int value) => value.toString().padLeft(2, '0');
}
