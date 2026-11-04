import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/bot/llbot_api_client.dart';

void main() {
  test('调用 OneBot HTTP API 发送消息', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));

    final requests = <Map<String, dynamic>>[];
    server.listen((request) async {
      final body = await utf8.decoder.bind(request).join();
      requests.add({
        'path': request.uri.path,
        'body': jsonDecode(body),
      });
      request.response.headers.contentType = ContentType.json;
      request.response.write(
        jsonEncode({
          'status': 'ok',
          'retcode': 0,
          'data': {'message_id': 42},
        }),
      );
      await request.response.close();
    });

    final client = LlbotApiClient(
      baseUrl: 'http://127.0.0.1:${server.port}',
    );

    final result = await client.sendPrivate(123456, '你好');
    expect(result.ok, isTrue);
    expect(result.messageId, 42);
    expect(requests.length, 1);
    expect(requests.first['path'], '/send_private_msg');
    final body = requests.first['body'] as Map<String, dynamic>;
    expect(body['user_id'], 123456);
    expect(body['message'], '你好');
  });
}
