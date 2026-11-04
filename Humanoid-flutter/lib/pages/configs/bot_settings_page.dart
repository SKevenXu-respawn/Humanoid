import 'package:flutter/material.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:humanoid_flutter/pages/home/widgets/home_item_title.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';
import 'package:humanoid_flutter/utils/bot/bot_prompt_service.dart';
import 'package:humanoid_flutter/utils/bot/bot_service.dart';
import 'package:humanoid_flutter/utils/database_util.dart';

class BotSettingsPage extends StatefulWidget {
  const BotSettingsPage({super.key});

  @override
  State<BotSettingsPage> createState() => _BotSettingsPageState();
}

class _BotSettingsPageState extends State<BotSettingsPage> {
  static const _accent = Color(0xFF3B82F6);
  static const _danger = Color(0xFFEF4444);

  final _llbotApiBase = TextEditingController();
  final _eventPort = TextEditingController();
  final _deepseekBase = TextEditingController();
  final _deepseekModel = TextEditingController();
  final _proactiveUsers = TextEditingController();
  final _userWhitelist = TextEditingController();
  final _groupWhitelist = TextEditingController();
  final _voiceSpeaker = TextEditingController();
  final _voiceResource = TextEditingController();
  final _asrResource = TextEditingController();
  final _asrModel = TextEditingController();
  final _qq = TextEditingController();
  final _userPrompt = TextEditingController();
  final _promptContent = TextEditingController();

  bool _loading = true;
  bool _saving = false;
  bool _shellEnabled = true;
  bool _voiceEnabled = true;
  bool _asrEnabled = true;
  String? _error;
  String _promptKey = BotPromptService.system;
  List<BotPromptInfo> _prompts = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final controller in [
      _llbotApiBase,
      _eventPort,
      _deepseekBase,
      _deepseekModel,
      _proactiveUsers,
      _userWhitelist,
      _groupWhitelist,
      _voiceSpeaker,
      _voiceResource,
      _asrResource,
      _asrModel,
      _qq,
      _userPrompt,
      _promptContent,
    ]) {
      controller.dispose();
    }
    super.dispose();
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
      _llbotApiBase.text =
          await DatabaseUtil.instance.getSetting('llbot_api_base') ??
              'http://127.0.0.1:3000';
      _eventPort.text =
          await DatabaseUtil.instance.getSetting('bot_event_port') ?? '13001';
      _deepseekBase.text =
          await DatabaseUtil.instance.getSetting('deepseek_base_url') ??
              'https://api.deepseek.com';
      _deepseekModel.text =
          await DatabaseUtil.instance.getSetting('deepseek_model') ??
              'deepseek-flash';
      _proactiveUsers.text =
          await DatabaseUtil.instance.getSetting('proactive_user_ids') ?? '';
      _voiceSpeaker.text =
          await DatabaseUtil.instance.getSetting('voice_speaker') ?? '';
      _voiceResource.text =
          await DatabaseUtil.instance.getSetting('voice_resource_id') ??
              'seed-tts-2.0';
      _asrResource.text =
          await DatabaseUtil.instance.getSetting('asr_resource_id') ??
              'volc.bigasr.auc_turbo';
      _asrModel.text =
          await DatabaseUtil.instance.getSetting('asr_model') ?? 'bigmodel';
      _shellEnabled =
          (await DatabaseUtil.instance.getSetting('shell_enabled') ?? '1') !=
              '0';
      _voiceEnabled =
          (await DatabaseUtil.instance.getSetting('voice_enabled') ?? '1') !=
              '0';
      _asrEnabled =
          (await DatabaseUtil.instance.getSetting('asr_enabled') ?? '1') !=
              '0';

      final repository = BotRepository();
      final users = await repository.listBotUsers();
      final groups = await repository.listBotGroups();
      _userWhitelist.text = users
          .where((row) => (row['enabled'] as int? ?? 0) == 1)
          .map((row) => row['user_id']?.toString() ?? '')
          .where((value) => value.isNotEmpty)
          .join(', ');
      _groupWhitelist.text = groups
          .where((row) => (row['enabled'] as int? ?? 0) == 1)
          .map((row) => row['group_id']?.toString() ?? '')
          .where((value) => value.isNotEmpty)
          .join(', ');

      _prompts = await BotPromptService().list();
      _promptContent.text = _promptFor(_promptKey);
      if (!mounted) {
        return;
      }
      setState(() => _loading = false);
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  String _promptFor(String key) {
    for (final prompt in _prompts) {
      if (prompt.key == key) {
        return prompt.content;
      }
    }
    return '';
  }

  Future<void> _saveSettings() async {
    if (_saving) {
      return;
    }
    setState(() => _saving = true);
    try {
      final util = DatabaseUtil.instance;
      await util.setSetting('llbot_api_base', _llbotApiBase.text.trim());
      await util.setSetting('bot_event_port', _eventPort.text.trim());
      await util.setSetting('deepseek_base_url', _deepseekBase.text.trim());
      await util.setSetting('deepseek_model', _deepseekModel.text.trim());
      await util.setSetting(
        'proactive_user_ids',
        _proactiveUsers.text.trim(),
      );
      await util.setSetting('voice_speaker', _voiceSpeaker.text.trim());
      await util.setSetting('voice_resource_id', _voiceResource.text.trim());
      await util.setSetting('asr_resource_id', _asrResource.text.trim());
      await util.setSetting('asr_model', _asrModel.text.trim());
      await util.setSetting('shell_enabled', _shellEnabled ? '1' : '0');
      await util.setSetting('voice_enabled', _voiceEnabled ? '1' : '0');
      await util.setSetting('asr_enabled', _asrEnabled ? '1' : '0');
      await util.db.insert(
        'bot_voice_config',
        {
          'id': 1,
          'enabled': _voiceEnabled ? 1 : 0,
          'speaker': _voiceSpeaker.text.trim(),
          'updated_at': DateTime.now().millisecondsSinceEpoch,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
      final repository = BotRepository();
      await repository.syncBotUsers(_parseIds(_userWhitelist.text));
      await repository.syncBotGroups(_parseIds(_groupWhitelist.text));
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('机器人设置已保存，重启机器人后生效')),
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

  Future<void> _savePrompt() async {
    try {
      await BotPromptService().save(_promptKey, _promptContent.text);
      _prompts = await BotPromptService().list();
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('提示词已保存')),
      );
    } catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('保存失败: $e')),
      );
    }
  }

  Future<void> _resetPrompt() async {
    try {
      await BotPromptService().reset(_promptKey);
      _prompts = await BotPromptService().list();
      setState(() => _promptContent.text = _promptFor(_promptKey));
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已恢复默认提示词')),
      );
    } catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('恢复失败: $e')),
      );
    }
  }

  Future<void> _loadUserPrompt() async {
    final userId = int.tryParse(_qq.text.trim());
    if (userId == null) {
      _show('请输入正确的 QQ 号');
      return;
    }
    final content = await BotPromptService().userPrompt(userId);
    setState(() => _userPrompt.text = content ?? '');
  }

  Future<void> _saveUserPrompt() async {
    final userId = int.tryParse(_qq.text.trim());
    if (userId == null) {
      _show('请输入正确的 QQ 号');
      return;
    }
    try {
      await BotPromptService().saveUserPrompt(userId, _userPrompt.text);
      _show('QQ $userId 的提示词已保存');
    } catch (e) {
      _show('保存失败: $e');
    }
  }

  Future<void> _deleteUserPrompt() async {
    final userId = int.tryParse(_qq.text.trim());
    if (userId == null) {
      _show('请输入正确的 QQ 号');
      return;
    }
    await BotPromptService().deleteUserPrompt(userId);
    setState(() => _userPrompt.clear());
    _show('已删除 QQ $userId 的自定义提示词');
  }

  void _show(String message) {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 16,
          children: [
            HomeItemTitle(
              title: '机器人设置',
              onReFresh: _loading || _saving ? null : _load,
            ),
            if (_loading)
              _card(_loadingView())
            else if (_error != null)
              _card(_errorView())
            else ...[
              _card(_connectionSection()),
              _card(_whitelistSection()),
              _card(_voiceSection()),
              _card(_promptSection()),
              _card(_userPromptSection()),
              FilledButton.icon(
                onPressed: _saving ? null : _saveSettings,
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
                label: Text(_saving ? '保存中...' : '保存机器人设置'),
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
                '当前状态：${BotService.instance.isRunning ? '运行中' : '未启动'}'
                ' · 事件端口 ${BotService.instance.eventPort}'
                ' · LLBot API ${BotService.instance.apiBaseUrl}',
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

  Widget _connectionSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        const _SectionTitle('连接与模型'),
        _field('LLBot API 地址', _llbotApiBase, hint: 'http://127.0.0.1:3000'),
        _field('事件监听端口', _eventPort, hint: '13001'),
        _field('DeepSeek Base URL', _deepseekBase, hint: 'https://api.deepseek.com'),
        _field('DeepSeek 模型', _deepseekModel, hint: 'deepseek-flash'),
        _field('主动行为限定 QQ', _proactiveUsers, hint: '留空表示全部白名单用户，多个用逗号分隔'),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('允许 AI 执行 Shell', style: TextStyle(fontSize: 13.5)),
          value: _shellEnabled,
          onChanged: (value) => setState(() => _shellEnabled = value),
        ),
      ],
    );
  }

  Widget _whitelistSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        const _SectionTitle('白名单'),
        _field(
          '允许私聊/群聊的 QQ',
          _userWhitelist,
          hint: '逗号分隔；留空表示不响应任何用户',
        ),
        _field(
          '允许的群号',
          _groupWhitelist,
          hint: '逗号分隔；留空表示不响应任何群',
        ),
        Text(
          '保存时会同步启用列表中的 QQ/群，未列出的会被停用。',
          style: TextStyle(
            fontSize: 11.5,
            color: Colors.black.withValues(alpha: 0.45),
          ),
        ),
      ],
    );
  }

  Widget _voiceSection() {    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        const _SectionTitle('语音与识别'),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('启用语音发送', style: TextStyle(fontSize: 13.5)),
          value: _voiceEnabled,
          onChanged: (value) => setState(() => _voiceEnabled = value),
        ),
        _field('TTS 音色 ID', _voiceSpeaker, hint: 'zh_female_jiaochuannv_uranus_bigtts'),
        _field('TTS Resource ID', _voiceResource, hint: 'seed-tts-2.0'),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('启用语音消息识别（ASR）', style: TextStyle(fontSize: 13.5)),
          value: _asrEnabled,
          onChanged: (value) => setState(() => _asrEnabled = value),
        ),
        _field('ASR Resource ID', _asrResource, hint: 'volc.bigasr.auc_turbo'),
        _field('ASR 模型', _asrModel, hint: 'bigmodel'),
      ],
    );
  }

  Widget _promptSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        const _SectionTitle('全局提示词'),
        DropdownButtonFormField<String>(
          initialValue: _promptKey,
          decoration: _decoration('选择提示词'),
          items: [
            for (final prompt in _prompts)
              DropdownMenuItem(
                value: prompt.key,
                child: Text('${prompt.label}（${prompt.source}）'),
              ),
          ],
          onChanged: (value) {
            if (value == null) {
              return;
            }
            setState(() {
              _promptKey = value;
              _promptContent.text = _promptFor(value);
            });
          },
        ),
        TextField(
          controller: _promptContent,
          minLines: 8,
          maxLines: 18,
          style: const TextStyle(fontFamily: 'Consolas', fontSize: 12.5),
          decoration: _decoration('提示词内容'),
        ),
        Row(
          children: [
            Expanded(
              child: FilledButton(
                onPressed: _savePrompt,
                child: const Text('保存提示词'),
              ),
            ),
            const SizedBox(width: 10),
            OutlinedButton(
              onPressed: _resetPrompt,
              style: OutlinedButton.styleFrom(foregroundColor: _danger),
              child: const Text('恢复默认'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _userPromptSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        const _SectionTitle('按 QQ 自定义系统提示词'),
        Row(
          children: [
            Expanded(
              child: _field('QQ 号', _qq, hint: '例如 123456789'),
            ),
            const SizedBox(width: 10),
            OutlinedButton(
              onPressed: _loadUserPrompt,
              child: const Text('读取'),
            ),
          ],
        ),
        TextField(
          controller: _userPrompt,
          minLines: 8,
          maxLines: 18,
          style: const TextStyle(fontFamily: 'Consolas', fontSize: 12.5),
          decoration: _decoration('留空表示使用全局系统提示词'),
        ),
        Row(
          children: [
            Expanded(
              child: FilledButton(
                onPressed: _saveUserPrompt,
                child: const Text('保存该 QQ 提示词'),
              ),
            ),
            const SizedBox(width: 10),
            OutlinedButton(
              onPressed: _deleteUserPrompt,
              style: OutlinedButton.styleFrom(foregroundColor: _danger),
              child: const Text('删除'),
            ),
          ],
        ),
      ],
    );
  }

  static List<int> _parseIds(String text) {
    return text
        .split(RegExp(r'[\s,，]+'))
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .map(int.tryParse)
        .whereType<int>()
        .toList(growable: false);
  }

  Widget _field(
    String label,
    TextEditingController controller, {
    String? hint,
  }) {
    return TextField(
      controller: controller,
      style: const TextStyle(fontSize: 13.5),
      decoration: _decoration(label, hint: hint),
    );
  }

  InputDecoration _decoration(String label, {String? hint}) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      isDense: true,
      filled: true,
      fillColor: Colors.white.withValues(alpha: 0.65),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: Colors.white.withValues(alpha: 0.85)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: _accent, width: 1.4),
      ),
    );
  }

  Widget _loadingView() {
    return Row(
      children: [
        const SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(strokeWidth: 2.2, color: _accent),
        ),
        const SizedBox(width: 14),
        Text(
          '正在读取机器人设置...',
          style: TextStyle(
            fontSize: 13,
            color: Colors.black.withValues(alpha: 0.55),
          ),
        ),
      ],
    );
  }

  Widget _errorView() {
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
        TextButton(onPressed: _load, child: const Text('重试')),
      ],
    );
  }

  Widget _card(Widget child) {
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

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        color: Color(0xDD000000),
      ),
    );
  }
}
