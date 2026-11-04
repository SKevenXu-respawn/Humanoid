import 'dart:async';

import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppEnum.dart';
import 'package:humanoid_flutter/pages/home/widgets/edit_default_config_dialog.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_item_title.dart';
import 'package:humanoid_flutter/pages/home/widgets/status_cards.dart';
import 'package:humanoid_flutter/utils/llonebot/llonebot_util.dart';

class HomeLLOneBotSection extends StatefulWidget {
  const HomeLLOneBotSection({super.key});

  @override
  State<HomeLLOneBotSection> createState() => _HomeLLOneBotSectionState();
}

class _HomeLLOneBotSectionState extends State<HomeLLOneBotSection> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);

  bool _refreshing = false;
  bool _installing = false;

  @override
  void initState() {
    super.initState();
    LLOneBotUtil.instance.checkStatus();
  }

  Future<void> _refresh() async {
    setState(() => _refreshing = true);
    await LLOneBotUtil.instance.checkStatus();
    if (mounted) {
      setState(() => _refreshing = false);
    }
  }

  Future<void> _confirmReinstall() async {
    if (_installing) {
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('删除并重新下载？'),
        content: const Text('这会删除 llonebot 文件夹中的全部内容，包括配置文件，删除后无法恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('删除并重装'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await _startInstall(reinstall: true);
    }
  }

  Future<void> _openDefaultConfigEditor() async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => const EditDefaultConfigDialog(),
    );
    if (saved == true && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('默认配置已保存，重启 LLOneBot 后生效')),
      );
    }
  }

  Future<void> _startInstall({required bool reinstall}) async {
    if (_installing) {
      return;
    }
    setState(() => _installing = true);

    final progress = ValueNotifier<LLOneBotProgress>(
      const LLOneBotProgress(
        stage: LLOneBotInstallStage.downloading,
        value: 0,
        message: '准备下载...',
      ),
    );

    unawaited(showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => _InstallProgressDialog(progress: progress),
    ));

    try {
      if (reinstall) {
        await LLOneBotUtil.instance.deleteInstall();
      }
      await LLOneBotUtil.instance.install(
        onProgress: (value) => progress.value = value,
      );

      if (!mounted) {
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 350));
      if (!mounted) {
        return;
      }

      final navigator = Navigator.of(context, rootNavigator: true);
      if (navigator.canPop()) {
        navigator.pop();
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('LLOneBot 安装完成')),
      );
    } catch (e) {
      progress.value = LLOneBotProgress(
        stage: LLOneBotInstallStage.failed,
        message: e.toString(),
      );
    } finally {
      if (mounted) {
        setState(() => _installing = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<LLOneBotStatus>(
      valueListenable: LLOneBotUtil.instance.statusNotifier,
      builder: (context, status, child) {
        final busy = status == LLOneBotStatus.downloading ||
            status == LLOneBotStatus.extracting;

        return Column(
          spacing: 16,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            HomeItemTitle(
              title: 'LLOneBot',
              onReFresh: _refreshing || _installing || busy ? null : _refresh,
            ),
            StatusCards(title: 'LLOneBot 运行环境', status: status),
            if (status == LLOneBotStatus.notInstalled ||
                status == LLOneBotStatus.error)
              FilledButton.icon(
                onPressed: _installing ? null : () => _startInstall(reinstall: false),
                icon: const Icon(Icons.download_rounded, size: 18),
                label: Text(
                  status == LLOneBotStatus.error
                      ? '重试安装 LLOneBot'
                      : '下载并安装 LLOneBot',
                ),
                style: FilledButton.styleFrom(
                  backgroundColor: _accent,
                  foregroundColor: Colors.white,
                  minimumSize: const Size.fromHeight(46),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
            if (status == LLOneBotStatus.installed) ...[
              OutlinedButton.icon(
                onPressed: _installing ? null : _confirmReinstall,
                icon: const Icon(Icons.delete_forever_rounded, size: 18),
                label: const Text('删除并重新下载'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: _danger,
                  backgroundColor: Colors.white.withValues(alpha: 0.55),
                  side: BorderSide(color: _danger.withValues(alpha: 0.35)),
                  minimumSize: const Size.fromHeight(46),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
              OutlinedButton.icon(
                onPressed: _installing ? null : _openDefaultConfigEditor,
                icon: const Icon(Icons.tune_rounded, size: 18),
                label: const Text('编辑默认配置'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: _accent,
                  backgroundColor: Colors.white.withValues(alpha: 0.55),
                  side: BorderSide(color: _accent.withValues(alpha: 0.35)),
                  minimumSize: const Size.fromHeight(46),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
            ],
            Text(
              '安装目录：${LLOneBotUtil.instance.installPath}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 11.5,
                color: Colors.black.withValues(alpha: 0.45),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _InstallProgressDialog extends StatelessWidget {
  final ValueNotifier<LLOneBotProgress> progress;

  const _InstallProgressDialog({required this.progress});

  static const _accent = Color(0xFF3B82F6);
  static const _extractColor = Color(0xFFF59E0B);
  static const _doneColor = Color(0xFF22C55E);
  static const _errorColor = Color(0xFFEF4444);

  Color _colorFor(LLOneBotInstallStage stage) {
    return switch (stage) {
      LLOneBotInstallStage.downloading => _accent,
      LLOneBotInstallStage.extracting => _extractColor,
      LLOneBotInstallStage.done => _doneColor,
      LLOneBotInstallStage.failed => _errorColor,
    };
  }

  IconData _iconFor(LLOneBotInstallStage stage) {
    return switch (stage) {
      LLOneBotInstallStage.downloading => Icons.cloud_download_rounded,
      LLOneBotInstallStage.extracting => Icons.archive_rounded,
      LLOneBotInstallStage.done => Icons.check_circle_rounded,
      LLOneBotInstallStage.failed => Icons.error_rounded,
    };
  }

  String _titleFor(LLOneBotInstallStage stage) {
    return switch (stage) {
      LLOneBotInstallStage.downloading => '正在下载 LLOneBot',
      LLOneBotInstallStage.extracting => '正在解压 LLOneBot',
      LLOneBotInstallStage.done => '安装完成',
      LLOneBotInstallStage.failed => '安装失败',
    };
  }

  String _labelFor(LLOneBotInstallStage stage) {
    return switch (stage) {
      LLOneBotInstallStage.downloading => '下载中',
      LLOneBotInstallStage.extracting => '解压中',
      LLOneBotInstallStage.done => '完成',
      LLOneBotInstallStage.failed => '失败',
    };
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 40),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Container(
          padding: const EdgeInsets.all(22),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Colors.white.withValues(alpha: 0.96),
                Colors.white.withValues(alpha: 0.86),
              ],
            ),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: Colors.white.withValues(alpha: 0.80)),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.18),
                blurRadius: 40,
                offset: const Offset(0, 20),
              ),
            ],
          ),
          child: ValueListenableBuilder<LLOneBotProgress>(
            valueListenable: progress,
            builder: (context, value, child) {
              final failed = value.stage == LLOneBotInstallStage.failed;
              final color = _colorFor(value.stage);

              return Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 46,
                        height: 46,
                        decoration: BoxDecoration(
                          color: color.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(
                            color: color.withValues(alpha: 0.20),
                          ),
                        ),
                        child: Icon(
                          _iconFor(value.stage),
                          color: color,
                          size: 24,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              _titleFor(value.stage),
                              style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                                color: Color(0xDD000000),
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              _labelFor(value.stage),
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: color,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  LinearProgressIndicator(
                    value: failed ? 0 : value.value,
                    minHeight: 8,
                    borderRadius: BorderRadius.circular(999),
                    backgroundColor: color.withValues(alpha: 0.12),
                    valueColor: AlwaysStoppedAnimation<Color>(color),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          value.message,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 12,
                            height: 1.4,
                            color: Colors.black.withValues(alpha: 0.50),
                          ),
                        ),
                      ),
                      if (!failed && value.value != null) ...[
                        const SizedBox(width: 12),
                        Text(
                          '${(value.value! * 100).round()}%',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: color,
                          ),
                        ),
                      ],
                    ],
                  ),
                  if (failed) ...[
                    const SizedBox(height: 16),
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: const Text('关闭'),
                      ),
                    ),
                  ],
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
