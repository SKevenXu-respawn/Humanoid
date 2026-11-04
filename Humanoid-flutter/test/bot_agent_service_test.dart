import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/bot_agent_service.dart';
import 'package:humanoid_flutter/utils/bot/bot_models.dart';
import 'package:humanoid_flutter/utils/bot/bot_paths.dart';
import 'package:humanoid_flutter/utils/bot/bot_skill_service.dart';
import 'package:humanoid_flutter/utils/bot/bot_tools.dart';
import 'package:humanoid_flutter/utils/bot/llbot_api_client.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  test('Agent 调用工具并生成最终回复', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    var requestCount = 0;
    final requestBodies = <Map<String, dynamic>>[];

    server.listen((request) async {
      final body = jsonDecode(await utf8.decoder.bind(request).join())
          as Map<String, dynamic>;
      requestBodies.add(body);
      requestCount++;
      request.response.headers.contentType = ContentType.json;

      if (requestCount == 1) {
        request.response.write(jsonEncode({
          'id': 'chatcmpl-test-1',
          'object': 'chat.completion',
          'created': 0,
          'model': 'test-model',
          'choices': [
            {
              'index': 0,
              'message': {
                'role': 'assistant',
                'content': null,
                'tool_calls': [
                  {
                    'id': 'call_1',
                    'type': 'function',
                    'function': {'name': 'currentTime', 'arguments': '{}'},
                  },
                ],
              },
              'finish_reason': 'tool_calls',
            },
          ],
          'usage': {
            'prompt_tokens': 1,
            'completion_tokens': 1,
            'total_tokens': 2,
          },
        }));
      } else {
        request.response.write(jsonEncode({
          'id': 'chatcmpl-test-2',
          'object': 'chat.completion',
          'created': 0,
          'model': 'test-model',
          'choices': [
            {
              'index': 0,
              'message': {'role': 'assistant', 'content': '现在是测试时间'},
              'finish_reason': 'stop',
            },
          ],
          'usage': {
            'prompt_tokens': 1,
            'completion_tokens': 1,
            'total_tokens': 2,
          },
        }));
      }
      await request.response.close();
    });

    await DatabaseUtil.instance.init(path: inMemoryDatabasePath);
    addTearDown(DatabaseUtil.instance.close);
    await DatabaseUtil.instance.saveSecrets({'deepseek_key': 'test-key'});
    await DatabaseUtil.instance.setSetting(
      'deepseek_base_url',
      'http://127.0.0.1:${server.port}',
    );
    await DatabaseUtil.instance.setSetting('deepseek_model', 'test-model');

    final baseDir = await Directory.systemTemp.createTemp('humanoid_agent_');
    addTearDown(() => baseDir.delete(recursive: true));
    await BotPaths.initWithBase(baseDir);
    final repository = BotRepository();
    final skills = BotSkillService(
      dirOverride: (userId) =>
          Directory(p.join(baseDir.path, '$userId', 'skills')),
    );
    final tools = BotToolRegistry(
      repository: repository,
      api: LlbotApiClient(baseUrl: 'http://127.0.0.1:1'),
      skills: skills,
    );
    final agent = BotAgentService(
      repository: repository,
      tools: tools,
      skills: skills,
    );

    final reply = await agent.generateReply(
      scope: const BotScope(userId: 10001, messageType: 'private'),
      userText: '现在几点？',
      currentContextId: 0,
    );

    expect(reply, '现在是测试时间');
    expect(requestCount, 2);
    expect(
      (requestBodies.first['tools'] as List)
          .any((tool) => tool['function']['name'] == 'currentTime'),
      isTrue,
    );
    expect(
      (requestBodies.last['messages'] as List)
          .any((message) => message['role'] == 'tool'),
      isTrue,
    );
  });
}
