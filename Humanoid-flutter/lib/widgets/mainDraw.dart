import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppConfig.dart';
import 'package:humanoid_flutter/models/mainDrawModels.dart';
import 'package:humanoid_flutter/pages/configs/secret_highlight_controller.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:humanoid_flutter/utils/llonebot/llonebot_util.dart';
import 'package:humanoid_flutter/utils/terminal/terminal_util.dart';

import '../config/AppColors.dart';

class MainDraw extends StatefulWidget {
  final void Function(MainDrawModels) callback;

  const MainDraw({super.key, required this.callback});

  @override
  State<MainDraw> createState() => _MainDrawState();
}

class _MainDrawState extends State<MainDraw> {
  bool _running = false;
  bool _starting = false;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: BoxConstraints(
        minWidth: context.screenWidth / 6,
        maxWidth: context.screenWidth / 6,
        minHeight: context.screenHeight,
        maxHeight: context.screenHeight,
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                  children: List.generate(AppConfig.mainDrawList.length, (
                    index,
                  ) {
                    final item = AppConfig.mainDrawList[index];
                    return _buildDrawItem(
                      item,
                      callback: (item) => widget.callback.call(item),
                    );
                  }),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(
                    vertical: 16,
                    horizontal: 8,
                  ),
                  child: AnimatedSize(
                    duration: const Duration(milliseconds: 300),
                    curve: Curves.easeInOutCubic,
                    alignment: Alignment.bottomCenter,
                    child: AnimatedSwitcher(
                      duration: const Duration(milliseconds: 300),
                      switchInCurve: Curves.easeOutCubic,
                      switchOutCurve: Curves.easeInCubic,
                      layoutBuilder: (currentChild, previousChildren) {
                        return Stack(
                          alignment: Alignment.bottomCenter,
                          children: [
                            ...previousChildren,
                            ?currentChild,
                          ],
                        );
                      },
                      transitionBuilder: (child, animation) {
                        return FadeTransition(
                          opacity: animation,
                          child: SizeTransition(
                            sizeFactor: animation,
                            alignment: Alignment.bottomCenter,
                            child: child,
                          ),
                        );
                      },
                      child: _running
                          ? _buildRunningButtons()
                          : _buildStartButton(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const VerticalDivider(width: 1),
        ],
      ),
    );
  }

  Widget _buildStartButton() {
    return KeyedSubtree(
      key: const ValueKey('stopped'),
      child: _buildDrawButton(
        _starting ? '检查中...' : '启动',
        Colors.blueAccent,
        _starting
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              )
            : const Icon(
                Icons.play_arrow_rounded,
                size: 16,
                color: Colors.white,
              ),
        _starting ? null : _handleStart,
      ),
    );
  }

  Widget _buildRunningButtons() {
    return Column(
      key: const ValueKey('running'),
      mainAxisSize: MainAxisSize.min,
      spacing: 16,
      children: [
        _buildDrawButton(
          '重启',
          Colors.orangeAccent,
          const Icon(
            Icons.replay_rounded,
            size: 16,
            color: Colors.white,
          ),
          _handleRestart,
        ),
        _buildDrawButton(
          '停止',
          Colors.redAccent,
          const Icon(
            Icons.stop_rounded,
            size: 16,
            color: Colors.white,
          ),
          _handleStop,
        ),
      ],
    );
  }

  Future<void> _handleStart() async {
    if (_starting) {
      return;
    }
    setState(() => _starting = true);

    try {
      final missing = await DatabaseUtil.instance.missingSecretKeys();
      if (!mounted) {
        return;
      }

      if (missing.isNotEmpty) {
        SecretHighlightController.highlight(missing);
        for (final item in AppConfig.mainDrawList) {
          if (item.name == '配置') {
            widget.callback.call(item);
            break;
          }
        }
        return;
      }

      SecretHighlightController.clear();

      if (!LLOneBotUtil.instance.isInstalled) {
        if (!mounted) {
          return;
        }
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('请先安装 LLOneBot')),
        );
        return;
      }

      final authToken = await DatabaseUtil.instance.getSecretValue(
        'llonebot_key',
      );
      if (authToken == null || authToken.trim().isEmpty) {
        if (!mounted) {
          return;
        }
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('LLOneBot Auth Token 未配置')),
        );
        return;
      }
      await LLOneBotUtil.instance.writeAuthToken(authToken);

      await TerminalUtil.instance.start();
      if (!mounted) {
        return;
      }

      if (TerminalUtil.instance.statusNotifier.value != TerminalStatus.running) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('终端启动失败，请查看日志')),
        );
        return;
      }

      await TerminalUtil.instance.sendCommand(
        await LLOneBotUtil.instance.buildWslStartCommand(),
      );
      if (!mounted) {
        return;
      }

      setState(() => _running = true);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已发送 LLOneBot 启动命令，请在日志页查看输出')),
      );
    } catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('启动失败: $e')),
      );
    } finally {
      if (mounted) {
        setState(() => _starting = false);
      }
    }
  }

  void _handleRestart() {
    setState(() => _running = true);
  }

  void _handleStop() {
    setState(() => _running = false);
  }

  Widget _buildDrawItem(
    MainDrawModels item, {
    required void Function(MainDrawModels) callback,
  }) {
    return Material(
      color: Colors.transparent,
      child: ListTile(
        title: Text(
          item.name,
          style: TextStyle(fontSize: 16, color: AppColors.black),
          textAlign: .center,
        ),
        titleAlignment: .center,
        onTap: () => callback.call(item),
      ),
    );
  }

  Widget _buildDrawButton(
    String title,
    Color btnColors,
    Widget icon,
    VoidCallback? onTap,
  ) {
    return ElevatedButton(
      style: ElevatedButton.styleFrom(
        backgroundColor: btnColors,
        elevation: 0,
        shadowColor: Colors.transparent,
        padding: .all(16),
        shape: RoundedRectangleBorder(borderRadius: .circular(12)),
      ),
      onPressed: onTap,
      child: Row(
        children: [
          icon,
          Text(title, style: TextStyle(fontSize: 16, color: Colors.white)),
        ],
      ),
    );
  }
}
