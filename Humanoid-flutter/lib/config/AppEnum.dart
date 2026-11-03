import 'package:flutter/material.dart';

abstract interface class AppStatus {
  String get title;
  Color get color;
  IconData get icon;
  String get hint;
  bool get shouldPulse;
}

enum DefaultStatus implements AppStatus {
  error(
    '错误',
    Colors.red,
    Icons.error_rounded,
    '操作失败，请稍后重试',
    false,
  ),
  success(
    '成功',
    Colors.greenAccent,
    Icons.check_circle_rounded,
    '操作已完成',
    false,
  );

  @override
  final String title;

  @override
  final Color color;

  @override
  final IconData icon;

  @override
  final String hint;

  @override
  final bool shouldPulse;

  const DefaultStatus(
    this.title,
    this.color,
    this.icon,
    this.hint,
    this.shouldPulse,
  );
}

enum WslStatus implements AppStatus {
  uninstall(
    '未安装 WSL',
    Color(0xFFEF4444),
    Icons.warning_amber_rounded,
    '未检测到 WSL，请先安装 WSL 和 Ubuntu',
    false,
  ),
  install(
    '缺少 Ubuntu',
    Color(0xFF3B82F6),
    Icons.download_rounded,
    'WSL 已安装，但未检测到 Ubuntu 发行版',
    false,
  ),
  run(
    '正在运行',
    Color(0xFF22C55E),
    Icons.play_circle_fill_rounded,
    'Ubuntu 正在运行，可以启动机器人服务',
    true,
  ),
  stop(
    '已停止',
    Color(0xFFF59E0B),
    Icons.pause_circle_filled_rounded,
    'Ubuntu 已安装，但当前没有运行',
    false,
  );

  @override
  final String title;

  @override
  final Color color;

  @override
  final IconData icon;

  @override
  final String hint;

  @override
  final bool shouldPulse;

  const WslStatus(
    this.title,
    this.color,
    this.icon,
    this.hint,
    this.shouldPulse,
  );
}

extension WslStatusExtension on WslStatus {
  /// 判断是否可以执行 wsl 命令
  bool get isAvailable => this == WslStatus.run || this == WslStatus.stop;

  /// 判断是否需要引导用户去安装或初始化
  bool get needsSetup => this == WslStatus.uninstall || this == WslStatus.install;
}

enum DatabaseStatus implements AppStatus {
  uninitialized(
    '未初始化',
    Color(0xFF64748B),
    Icons.storage_rounded,
    '本地数据库尚未打开',
    false,
  ),
  initializing(
    '初始化中',
    Color(0xFF3B82F6),
    Icons.sync_rounded,
    '正在打开本地数据库...',
    true,
  ),
  ready(
    '已就绪',
    Color(0xFF22C55E),
    Icons.check_circle_rounded,
    '本地数据库已就绪，可以正常读写配置',
    false,
  ),
  error(
    '异常',
    Color(0xFFEF4444),
    Icons.error_rounded,
    '数据库初始化失败，请检查日志后重试',
    false,
  );

  @override
  final String title;

  @override
  final Color color;

  @override
  final IconData icon;

  @override
  final String hint;

  @override
  final bool shouldPulse;

  const DatabaseStatus(
    this.title,
    this.color,
    this.icon,
    this.hint,
    this.shouldPulse,
  );
}

enum LLOneBotStatus implements AppStatus {
  checking(
    '检测中',
    Color(0xFF3B82F6),
    Icons.search_rounded,
    '正在检查 llonebot 文件夹',
    true,
  ),
  notInstalled(
    '未安装',
    Color(0xFF64748B),
    Icons.download_rounded,
    '未检测到 llonebot 文件夹，请先下载安装',
    false,
  ),
  downloading(
    '下载中',
    Color(0xFF3B82F6),
    Icons.cloud_download_rounded,
    '正在下载 LLOneBot 安装包',
    true,
  ),
  extracting(
    '解压中',
    Color(0xFFF59E0B),
    Icons.archive_rounded,
    '正在解压 LLOneBot 安装包',
    true,
  ),
  installed(
    '已安装',
    Color(0xFF22C55E),
    Icons.check_circle_rounded,
    'LLOneBot 已安装，可以进行后续配置',
    false,
  ),
  error(
    '安装失败',
    Color(0xFFEF4444),
    Icons.error_rounded,
    'LLOneBot 安装失败，请重试',
    false,
  );

  @override
  final String title;

  @override
  final Color color;

  @override
  final IconData icon;

  @override
  final String hint;

  @override
  final bool shouldPulse;

  const LLOneBotStatus(
    this.title,
    this.color,
    this.icon,
    this.hint,
    this.shouldPulse,
  );
}
