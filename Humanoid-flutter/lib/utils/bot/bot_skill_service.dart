import 'dart:io';

import 'package:path/path.dart' as p;

import 'bot_paths.dart';

class BotSkill {
  final String name;
  final String description;
  final String content;

  const BotSkill({
    required this.name,
    required this.description,
    required this.content,
  });
}

class BotSkillService {
  BotSkillService({Directory Function(int userId)? dirOverride})
      : _dirOverride = dirOverride;

  final Directory Function(int userId)? _dirOverride;

  static final _namePattern = RegExp(r'^[a-zA-Z0-9_-]{1,64}$');

  Directory _skillsDir(int userId) =>
      _dirOverride?.call(userId) ?? BotPaths.userSkillsDir(userId);

  Future<List<BotSkill>> list(int userId) async {
    final dir = _skillsDir(userId);
    await dir.create(recursive: true);
    final skills = <BotSkill>[];
    await for (final entity in dir.list()) {
      if (entity is! File) {
        continue;
      }
      final name = p.basenameWithoutExtension(entity.path);
      if (!_namePattern.hasMatch(name)) {
        continue;
      }
      try {
        skills.add(_parse(await entity.readAsString(), fallbackName: name));
      } catch (_) {
        // Ignore unreadable skill files.
      }
    }
    skills.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    return skills;
  }

  Future<BotSkill?> read(int userId, String name) async {
    final skillName = name.trim();
    if (!_namePattern.hasMatch(skillName)) {
      return null;
    }
    final file = File(p.join(_skillsDir(userId).path, '$skillName.md'));
    if (!await file.exists()) {
      return null;
    }
    return _parse(await file.readAsString(), fallbackName: skillName);
  }

  Future<BotSkill> register({
    required int userId,
    required String name,
    required String description,
    required String content,
  }) async {
    final skillName = name.trim();
    if (!_namePattern.hasMatch(skillName)) {
      throw ArgumentError('技能名称只能包含字母、数字、下划线和短横线，长度 1-64');
    }
    final desc = description.trim();
    if (desc.length > 300) {
      throw ArgumentError('技能描述不能超过 300 字');
    }
    final body = content.trim();
    if (body.isEmpty) {
      throw ArgumentError('技能内容不能为空');
    }
    final dir = _skillsDir(userId);
    await dir.create(recursive: true);
    final file = File(p.join(dir.path, '$skillName.md'));
    await file.writeAsString(
      '---\nname: $skillName\ndescription: $desc\n---\n\n$body\n',
      flush: true,
    );
    return BotSkill(name: skillName, description: desc, content: body);
  }

  Future<String> brief({required int userId, int limit = 30}) async {
    final skills = await list(userId);
    if (skills.isEmpty) {
      return '';
    }
    final buffer = StringBuffer();
    for (final skill in skills.take(limit)) {
      buffer.writeln('- ${skill.name}：${skill.description}');
    }
    return buffer.toString().trimRight();
  }

  static BotSkill _parse(String raw, {required String fallbackName}) {
    var name = fallbackName;
    var description = '';
    var body = raw.trim();
    if (body.startsWith('---')) {
      final end = body.indexOf('\n---', 3);
      if (end != -1) {
        final meta = body.substring(3, end);
        body = body.substring(end + 4).trimLeft();
        for (final line in meta.split('\n')) {
          final separator = line.indexOf(':');
          if (separator <= 0) {
            continue;
          }
          final key = line.substring(0, separator).trim().toLowerCase();
          final value = line.substring(separator + 1).trim();
          if (key == 'name' && value.isNotEmpty) {
            name = value;
          } else if (key == 'description' && value.isNotEmpty) {
            description = value;
          }
        }
      }
    }
    if (description.isEmpty) {
      description = body
          .split('\n')
          .map((line) => line.trim())
          .firstWhere((line) => line.isNotEmpty, orElse: () => name);
    }
    return BotSkill(name: name, description: description, content: body);
  }
}
