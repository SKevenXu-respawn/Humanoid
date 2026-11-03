import 'package:flutter/material.dart';
import 'package:humanoid_flutter/pages/configs/secret_highlight_controller.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_item_title.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:url_launcher/url_launcher.dart';

class ConfigsPage extends StatefulWidget {
  const ConfigsPage({super.key});

  @override
  State<ConfigsPage> createState() => _ConfigsPageState();
}

class _ConfigsPageState extends State<ConfigsPage> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);
  static const _labels = <String, String>{
    'deepseek_key': 'DeepSeek Key',
    'llonebot_key': 'LLOneBot Key',
    'speech_key': '语音服务 Key',
  };

  bool _loading = true;
  bool _saving = false;
  Object? _error;
  List<_SecretItem> _items = [];
  final Map<String, TextEditingController> _controllers = {};

  @override
  void initState() {
    super.initState();
    SecretHighlightController.keysNotifier.addListener(_handleHighlightChanged);
    _load();
  }

  @override
  void dispose() {
    SecretHighlightController.keysNotifier.removeListener(
      _handleHighlightChanged,
    );
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  void _handleHighlightChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      if (!DatabaseUtil.instance.isInitialized) {
        await DatabaseUtil.instance.init();
      }

      final rows = await DatabaseUtil.instance.listSecrets();
      final items = <_SecretItem>[];
      final controllers = <String, TextEditingController>{};

      for (final row in rows) {
        final key = row['secret_key'] as String? ?? '';
        if (key.isEmpty) {
          continue;
        }
        final rawName = row['secret_name'] as String?;
        items.add(_SecretItem(
          key: key,
          name: (rawName == null || rawName.trim().isEmpty)
              ? (_labels[key] ?? key)
              : rawName.trim(),
          url: row['secret_url'] as String? ?? '',
        ));
        controllers[key] = TextEditingController(
          text: row['secret_value'] as String? ?? '',
        );
      }

      if (!mounted) {
        for (final controller in controllers.values) {
          controller.dispose();
        }
        return;
      }

      for (final controller in _controllers.values) {
        controller.dispose();
      }
      _controllers
        ..clear()
        ..addAll(controllers);

      setState(() {
        _items = items;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  Future<void> _save() async {
    if (_saving) {
      return;
    }
    setState(() => _saving = true);

    try {
      final values = <String, String?>{};
      for (final item in _items) {
        final text = _controllers[item.key]?.text.trim() ?? '';
        values[item.key] = text.isEmpty ? null : text;
      }
      await DatabaseUtil.instance.saveSecrets(values);

      final missing = values.entries
          .where((entry) => (entry.value ?? '').trim().isEmpty)
          .map((entry) => entry.key)
          .toList();
      SecretHighlightController.highlight(missing);

      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('配置已保存')),
      );
    } catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('保存失败: $e')),
      );
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  Future<void> _openUrl(String url) async {
    final uri = Uri.tryParse(url);
    if (uri == null) {
      return;
    }
    final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!opened && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('无法打开链接: $url')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final highlighted = SecretHighlightController.keysNotifier.value;

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          spacing: 16,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            HomeItemTitle(
              title: '密钥配置',
              onReFresh: _loading || _saving ? null : _load,
            ),
            if (highlighted.isNotEmpty) _buildHighlightBanner(),
            if (_loading)
              _glassCard(_buildLoading())
            else if (_error != null)
              _glassCard(_buildError())
            else if (_items.isEmpty)
              _glassCard(
                Text(
                  '暂无密钥配置',
                  style: TextStyle(
                    fontSize: 13,
                    color: Colors.black.withValues(alpha: 0.55),
                  ),
                ),
              )
            else ...[
              _glassCard(
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 18,
                  children: [
                    for (final item in _items) _buildSecretField(item),
                  ],
                ),
              ),
              FilledButton.icon(
                onPressed: _saving ? null : _save,
                icon: _saving
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.save_rounded, size: 18),
                label: Text(_saving ? '保存中...' : '保存配置'),
                style: FilledButton.styleFrom(
                  backgroundColor: _accent,
                  foregroundColor: Colors.white,
                  minimumSize: const Size.fromHeight(46),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
              Text(
                '密钥保存在本地 humanoid.db 的 bot_secret 表中。',
                style: TextStyle(
                  fontSize: 11.5,
                  color: Colors.black.withValues(alpha: 0.45),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildHighlightBanner() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: _danger.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: _danger.withValues(alpha: 0.30)),
      ),
      child: Row(
        children: [
          const Icon(Icons.error_outline_rounded, color: _danger, size: 18),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              '请先填写缺失的密钥',
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w500,
                color: _danger,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSecretField(_SecretItem item) {
    final highlighted =
        SecretHighlightController.keysNotifier.value.contains(item.key);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                item.name,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: highlighted ? _danger : const Color(0xDD000000),
                ),
              ),
            ),
            if (highlighted) ...[
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 8,
                  vertical: 3,
                ),
                decoration: BoxDecoration(
                  color: _danger.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: const Text(
                  '必填',
                  style: TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w600,
                    color: _danger,
                  ),
                ),
              ),
              const SizedBox(width: 8),
            ],
            Text(
              item.key,
              style: TextStyle(
                fontSize: 11.5,
                color: Colors.black.withValues(alpha: 0.35),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _controllers[item.key],
          onChanged: (value) {
            if (value.trim().isNotEmpty) {
              SecretHighlightController.clearKey(item.key);
            }
          },
          style: const TextStyle(fontSize: 13.5, color: Color(0xDD000000)),
          decoration: InputDecoration(
            hintText: '未配置',
            isDense: true,
            filled: true,
            fillColor: highlighted
                ? _danger.withValues(alpha: 0.06)
                : Colors.white.withValues(alpha: 0.65),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 14,
              vertical: 12,
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(
                color: highlighted
                    ? _danger
                    : Colors.white.withValues(alpha: 0.85),
                width: highlighted ? 1.4 : 1,
              ),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(
                color: highlighted ? _danger : _accent,
                width: 1.4,
              ),
            ),
          ),
        ),
        if (item.url.isNotEmpty) ...[
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: () => _openUrl(item.url),
              icon: const Icon(Icons.open_in_new_rounded, size: 14),
              label: const Text('获取 Key'),
              style: TextButton.styleFrom(
                foregroundColor: _accent,
                padding: EdgeInsets.zero,
                minimumSize: const Size(0, 28),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                textStyle: const TextStyle(fontSize: 11.5),
              ),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildLoading() {
    return Row(
      children: [
        const SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(
            strokeWidth: 2.2,
            color: _accent,
          ),
        ),
        const SizedBox(width: 14),
        Text(
          '正在读取密钥配置...',
          style: TextStyle(
            fontSize: 13,
            color: Colors.black.withValues(alpha: 0.55),
          ),
        ),
      ],
    );
  }

  Widget _buildError() {
    return Row(
      children: [
        const Icon(Icons.error_rounded, color: _danger, size: 26),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            '读取失败：$_error',
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 13,
              color: Colors.black.withValues(alpha: 0.55),
            ),
          ),
        ),
        TextButton(
          onPressed: _load,
          child: const Text('重试'),
        ),
      ],
    );
  }

  Widget _glassCard(Widget child) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Colors.white.withValues(alpha: 0.84),
            Colors.white.withValues(alpha: 0.60),
          ],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withValues(alpha: 0.70)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 24,
            offset: const Offset(0, 12),
          ),
          BoxShadow(
            color: _accent.withValues(alpha: 0.14),
            blurRadius: 30,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: child,
    );
  }
}

class _SecretItem {
  final String key;
  final String name;
  final String url;

  const _SecretItem({required this.key, required this.name, required this.url});
}
