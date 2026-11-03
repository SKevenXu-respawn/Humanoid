import 'package:flutter/material.dart';
import 'package:humanoid_flutter/utils/terminal/terminal_util.dart';

class LogsPage extends StatefulWidget {
  const LogsPage({super.key});

  @override
  State<LogsPage> createState() => _LogsPageState();
}

class _LogsPageState extends State<LogsPage> {
  final TextEditingController _commandController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final FocusNode _focusNode = FocusNode();

  @override
  void dispose() {
    _commandController.dispose();
    _scrollController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _send() {
    final text = _commandController.text;
    if (text.trim().isEmpty) {
      return;
    }
    TerminalUtil.instance.sendCommand(text);
    _commandController.clear();
    if (mounted) {
      _focusNode.requestFocus();
    }
  }

  void _scrollToBottom() {
    if (!_scrollController.hasClients) {
      return;
    }
    _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Container(
        color: const Color(0xFF1F2937).withValues(alpha: 0.55),
        padding: const EdgeInsets.all(16),
        child: ValueListenableBuilder<TerminalStatus>(
          valueListenable: TerminalUtil.instance.statusNotifier,
          builder: (context, status, child) {
            final busy = status == TerminalStatus.starting ||
                status == TerminalStatus.stopping;
            final running = status == TerminalStatus.running;

            return Column(
              children: [
                _buildHeader(status, busy, running),
                const SizedBox(height: 12),
                Expanded(child: _buildLogs()),
                const SizedBox(height: 12),
                _buildInput(status),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildHeader(TerminalStatus status, bool busy, bool running) {
    final color = _statusColor(status);
    final label = _statusLabel(status);

    return Row(
      children: [
        const Icon(Icons.terminal_rounded, color: Colors.white70, size: 20),
        const SizedBox(width: 8),
        const Text(
          '终端日志',
          style: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
        const SizedBox(width: 12),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: color.withValues(alpha: 0.35)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 6,
                height: 6,
                decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              ),
              const SizedBox(width: 5),
              Text(
                label,
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w600,
                  color: color,
                ),
              ),
            ],
          ),
        ),
        const Spacer(),
        _TerminalButton(
          label: '启动',
          icon: Icons.play_arrow_rounded,
          color: const Color(0xFF22C55E),
          onPressed: busy || running ? null : () => TerminalUtil.instance.start(),
        ),
        const SizedBox(width: 8),
        _TerminalButton(
          label: '重启',
          icon: Icons.restart_alt_rounded,
          color: const Color(0xFF60A5FA),
          onPressed:
              busy || !running ? null : () => TerminalUtil.instance.restart(),
        ),
        const SizedBox(width: 8),
        _TerminalButton(
          label: '关闭',
          icon: Icons.stop_rounded,
          color: const Color(0xFFEF4444),
          onPressed: busy || !running ? null : () => TerminalUtil.instance.stop(),
        ),
      ],
    );
  }

  Widget _buildLogs() {
    return Container(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.28),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
      ),
      child: ValueListenableBuilder<List<TerminalLogEntry>>(
        valueListenable: TerminalUtil.instance.logsNotifier,
        builder: (context, logs, child) {
          WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToBottom());

          if (logs.isEmpty) {
            return const Center(
              child: Text(
                '暂无日志',
                style: TextStyle(color: Colors.white38, fontSize: 13),
              ),
            );
          }

          return SelectionArea(
            child: ListView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.all(12),
              itemCount: logs.length,
              itemBuilder: (context, index) {
                final entry = logs[index];
                final isCommand = entry.type == TerminalLogType.command;
                return Padding(
                  padding: const EdgeInsets.only(bottom: 2),
                  child: Text(
                    isCommand ? '> ${entry.text}' : entry.text,
                    style: TextStyle(
                      fontFamily: 'Consolas',
                      fontSize: 12.5,
                      height: 1.5,
                      color: _logColor(entry.type),
                      fontWeight:
                          isCommand ? FontWeight.w600 : FontWeight.normal,
                    ),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }

  Widget _buildInput(TerminalStatus status) {
    final enabled = status == TerminalStatus.running;

    return TextField(
      controller: _commandController,
      focusNode: _focusNode,
      enabled: enabled,
      onSubmitted: (_) => _send(),
      style: const TextStyle(
        color: Colors.white,
        fontFamily: 'Consolas',
        fontSize: 13,
      ),
      cursorColor: Colors.white70,
      decoration: InputDecoration(
        hintText: enabled ? '输入命令，回车执行' : '终端未启动',
        hintStyle: const TextStyle(color: Colors.white38, fontSize: 13),
        prefixText: enabled ? '> ' : null,
        prefixStyle: const TextStyle(
          color: Colors.white54,
          fontFamily: 'Consolas',
        ),
        filled: true,
        fillColor: Colors.black.withValues(alpha: 0.25),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: Colors.white.withValues(alpha: 0.30)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: Color(0xFF60A5FA), width: 1.4),
        ),
        disabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: Colors.white.withValues(alpha: 0.12)),
        ),
        suffixIcon: IconButton(
          onPressed: enabled ? _send : null,
          icon: const Icon(Icons.send_rounded, size: 18),
          color: const Color(0xFF60A5FA),
          disabledColor: Colors.white24,
          tooltip: '发送',
        ),
      ),
    );
  }

  static String _statusLabel(TerminalStatus status) {
    return switch (status) {
      TerminalStatus.stopped => '已停止',
      TerminalStatus.starting => '启动中',
      TerminalStatus.running => '运行中',
      TerminalStatus.stopping => '关闭中',
      TerminalStatus.error => '启动失败',
    };
  }

  static Color _statusColor(TerminalStatus status) {
    return switch (status) {
      TerminalStatus.stopped => const Color(0xFF94A3B8),
      TerminalStatus.starting => const Color(0xFF60A5FA),
      TerminalStatus.running => const Color(0xFF22C55E),
      TerminalStatus.stopping => const Color(0xFFF59E0B),
      TerminalStatus.error => const Color(0xFFEF4444),
    };
  }

  static Color _logColor(TerminalLogType type) {
    return switch (type) {
      TerminalLogType.command => const Color(0xFF93C5FD),
      TerminalLogType.stdout => Colors.white.withValues(alpha: 0.82),
      TerminalLogType.stderr => const Color(0xFFFF8A80),
      TerminalLogType.system => Colors.white.withValues(alpha: 0.42),
    };
  }
}

class _TerminalButton extends StatelessWidget {
  final String label;
  final IconData icon;
  final Color color;
  final VoidCallback? onPressed;

  const _TerminalButton({
    required this.label,
    required this.icon,
    required this.color,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;

    return TextButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 16),
      label: Text(label),
      style: TextButton.styleFrom(
        foregroundColor: enabled ? color : Colors.white24,
        backgroundColor:
            (enabled ? color : Colors.white).withValues(alpha: 0.10),
        side: BorderSide(
          color: enabled ? color.withValues(alpha: 0.35) : Colors.transparent,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        minimumSize: const Size(0, 34),
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        textStyle: const TextStyle(fontSize: 12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      ),
    );
  }
}
