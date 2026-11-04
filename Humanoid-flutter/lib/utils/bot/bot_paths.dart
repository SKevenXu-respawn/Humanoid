import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class BotPaths {
  BotPaths._();

  static late Directory baseDir;
  static bool _initialized = false;

  static bool get isInitialized => _initialized;

  static Future<void> init() async {
    if (_initialized) {
      return;
    }
    final support = await getApplicationSupportDirectory();
    await initWithBase(Directory(p.join(support.path, 'bot_files')));
  }

  static Future<void> initWithBase(Directory base) async {
    baseDir = base;
    await base.create(recursive: true);
    _initialized = true;
  }

  static Directory userRoot(int userId) =>
      Directory(p.join(baseDir.path, '$userId'));

  static Directory userMemoryDir(int userId) =>
      Directory(p.join(userRoot(userId).path, 'memory'));

  static Directory userSkillsDir(int userId) =>
      Directory(p.join(userRoot(userId).path, 'skills'));

  static Directory userToolsDir(int userId) =>
      Directory(p.join(userRoot(userId).path, 'tools'));

  static Directory userDownloadsDir(int userId) =>
      Directory(p.join(userRoot(userId).path, 'downloads'));

  static Future<void> ensureUserDirs(int userId) async {
    await userMemoryDir(userId).create(recursive: true);
    await userSkillsDir(userId).create(recursive: true);
    await userToolsDir(userId).create(recursive: true);
    await userDownloadsDir(userId).create(recursive: true);
  }
}
