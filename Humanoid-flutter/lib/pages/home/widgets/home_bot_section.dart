import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppEnum.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_item_title.dart';
import 'package:humanoid_flutter/pages/home/widgets/status_cards.dart';
import 'package:humanoid_flutter/utils/bot/bot_service.dart';

class HomeBotSection extends StatefulWidget {
  const HomeBotSection({super.key});

  @override
  State<HomeBotSection> createState() => _HomeBotSectionState();
}

class _HomeBotSectionState extends State<HomeBotSection> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);

  bool _busy = false;

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) {
      return;
    }
    setState(() => _busy = true);
    try {
      await action();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<BotStatus>(
      valueListenable: BotService.instance.statusNotifier,
      builder: (context, status, child) {
        final running = status == BotStatus.running;
        final busy = _busy || status == BotStatus.starting;

        return Column(
          spacing: 16,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const HomeItemTitle(title: 'QQ 机器人'),
            StatusCards(title: '机器人服务', status: status),
            if (!running)
              FilledButton.icon(
                onPressed: busy
                    ? null
                    : () => _run(BotService.instance.start),
                icon: const Icon(Icons.play_arrow_rounded, size: 18),
                label: const Text('启动机器人'),
                style: FilledButton.styleFrom(
                  backgroundColor: _accent,
                  foregroundColor: Colors.white,
                  minimumSize: const Size.fromHeight(46),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
            if (running) ...[
              OutlinedButton.icon(
                onPressed: busy
                    ? null
                    : () => _run(BotService.instance.restart),
                icon: const Icon(Icons.restart_alt_rounded, size: 18),
                label: const Text('重启机器人'),
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
              OutlinedButton.icon(
                onPressed: busy
                    ? null
                    : () => _run(BotService.instance.stop),
                icon: const Icon(Icons.stop_rounded, size: 18),
                label: const Text('停止机器人'),
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
            ],
            Text(
              '事件监听：0.0.0.0:${BotService.instance.eventPort}'
              ' · LLBot API：${BotService.instance.apiBaseUrl}',
              maxLines: 2,
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
