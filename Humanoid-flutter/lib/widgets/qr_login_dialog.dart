import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:humanoid_flutter/utils/terminal/terminal_util.dart';

class QrLoginDialog extends StatefulWidget {
  const QrLoginDialog({super.key});

  @override
  State<QrLoginDialog> createState() => _QrLoginDialogState();
}

class _QrLoginDialogState extends State<QrLoginDialog> {
  @override
  void initState() {
    super.initState();
    TerminalUtil.instance.loginSuccessNotifier.addListener(_handleLoginSuccess);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && TerminalUtil.instance.loginSuccessNotifier.value) {
        Navigator.of(context).maybePop();
      }
    });
  }

  @override
  void dispose() {
    TerminalUtil.instance.loginSuccessNotifier.removeListener(
      _handleLoginSuccess,
    );
    super.dispose();
  }

  void _handleLoginSuccess() {
    if (!mounted) {
      return;
    }
    if (!TerminalUtil.instance.loginSuccessNotifier.value) {
      return;
    }
    Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 40, vertical: 24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360),
        child: Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.20),
                blurRadius: 40,
                offset: const Offset(0, 20),
              ),
            ],
          ),
          child: ValueListenableBuilder<QrCodeInfo?>(
            valueListenable: TerminalUtil.instance.qrCodeNotifier,
            builder: (context, info, child) {
              return Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      const Icon(
                        Icons.qr_code_2_rounded,
                        color: Color(0xFF3B82F6),
                        size: 24,
                      ),
                      const SizedBox(width: 8),
                      const Expanded(
                        child: Text(
                          'QQ 登录二维码',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            color: Color(0xDD000000),
                          ),
                        ),
                      ),
                      IconButton(
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.close_rounded, size: 20),
                        color: Colors.black45,
                        tooltip: '关闭',
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Container(
                    width: 268,
                    height: 268,
                    padding: const EdgeInsets.all(6),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: Colors.black.withValues(alpha: 0.08),
                      ),
                    ),
                    child: info == null
                        ? const Center(
                            child: Text(
                              '二维码已失效',
                              style: TextStyle(
                                fontSize: 13,
                                color: Colors.black45,
                              ),
                            ),
                          )
                        : _QrImage(info: info),
                  ),
                  const SizedBox(height: 14),
                  const Text(
                    '请使用手机 QQ 扫码登录',
                    style: TextStyle(fontSize: 12.5, color: Colors.black54),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '二维码失效后会自动刷新',
                    style: TextStyle(
                      fontSize: 11,
                      color: Colors.black.withValues(alpha: 0.35),
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

class _QrImage extends StatefulWidget {
  final QrCodeInfo info;

  const _QrImage({required this.info});

  @override
  State<_QrImage> createState() => _QrImageState();
}

class _QrImageState extends State<_QrImage> {
  Future<Uint8List>? _future;

  @override
  void initState() {
    super.initState();
    _prepare();
  }

  @override
  void didUpdateWidget(covariant _QrImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.info, widget.info)) {
      _prepare();
    }
  }

  void _prepare() {
    final path = widget.info.windowsPath;
    _future = (path == null || path.isEmpty)
        ? null
        : File(path).readAsBytes();
  }

  @override
  Widget build(BuildContext context) {
    final future = _future;
    if (future == null) {
      return _networkImage();
    }

    return FutureBuilder<Uint8List>(
      future: future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(
            child: SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2.4),
            ),
          );
        }

        final bytes = snapshot.data;
        if (snapshot.hasError || bytes == null || bytes.isEmpty) {
          return _networkImage();
        }

        return Image.memory(
          bytes,
          fit: BoxFit.contain,
          filterQuality: FilterQuality.medium,
          gaplessPlayback: true,
        );
      },
    );
  }

  Widget _networkImage() {
    final url = widget.info.url;
    if (url == null || url.isEmpty) {
      return _errorPlaceholder();
    }

    return Image.network(
      url,
      fit: BoxFit.contain,
      loadingBuilder: (context, child, progress) {
        if (progress == null) {
          return child;
        }
        return const Center(
          child: SizedBox(
            width: 24,
            height: 24,
            child: CircularProgressIndicator(strokeWidth: 2.4),
          ),
        );
      },
      errorBuilder: (context, error, stackTrace) => _errorPlaceholder(),
    );
  }

  Widget _errorPlaceholder() {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.qr_code_2_rounded, size: 52, color: Colors.black26),
          SizedBox(height: 8),
          Text(
            '二维码加载失败，请查看日志',
            style: TextStyle(fontSize: 12, color: Colors.black45),
          ),
        ],
      ),
    );
  }
}
