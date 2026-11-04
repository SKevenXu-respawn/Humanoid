import 'dart:convert';

import 'package:dio/dio.dart';

class LlbotApiResult {
  final bool ok;
  final int? messageId;
  final String? error;

  const LlbotApiResult({required this.ok, this.messageId, this.error});

  static const failure = LlbotApiResult(ok: false);
}

class LlbotApiClient {
  LlbotApiClient({
    String baseUrl = 'http://127.0.0.1:3000',
    String? token,
  }) : _dio = Dio(
          BaseOptions(
            baseUrl: baseUrl,
            connectTimeout: const Duration(seconds: 10),
            receiveTimeout: const Duration(seconds: 180),
            sendTimeout: const Duration(seconds: 30),
            headers: {
              'Content-Type': 'application/json',
              if (token != null && token.trim().isNotEmpty)
                'Authorization': 'Bearer ${token.trim()}',
            },
          ),
        );

  final Dio _dio;

  Future<LlbotApiResult> sendPrivate(int userId, String text) {
    return _sendMessage('send_private_msg', {
      'user_id': userId,
      'message': text,
    });
  }

  Future<LlbotApiResult> sendGroup(int groupId, String text) {
    return _sendMessage('send_group_msg', {
      'group_id': groupId,
      'message': text,
    });
  }

  Future<LlbotApiResult> sendImage({
    required int userId,
    int? groupId,
    required String image,
  }) {
    final segments = [
      {
        'type': 'image',
        'data': {'file': image},
      },
    ];
    return _sendMessage(
      groupId != null ? 'send_group_msg' : 'send_private_msg',
      groupId != null
          ? {'group_id': groupId, 'message': segments}
          : {'user_id': userId, 'message': segments},
    );
  }

  Future<LlbotApiResult> sendVoice({
    required int userId,
    int? groupId,
    required List<int> audio,
  }) {
    return sendSegments(
      userId: userId,
      groupId: groupId,
      segments: [
        {
          'type': 'record',
          'data': {'file': 'base64://${base64Encode(audio)}'},
        },
      ],
    );
  }

  Future<LlbotApiResult> sendFace({
    required int userId,
    int? groupId,
    required int faceId,
  }) {
    return sendSegments(
      userId: userId,
      groupId: groupId,
      segments: [
        {
          'type': 'face',
          'data': {'id': faceId},
        },
      ],
    );
  }

  Future<LlbotApiResult> sendSegments({
    required int userId,
    int? groupId,
    required List<Map<String, dynamic>> segments,
  }) {
    return _sendMessage(
      groupId != null ? 'send_group_msg' : 'send_private_msg',
      groupId != null
          ? {'group_id': groupId, 'message': segments}
          : {'user_id': userId, 'message': segments},
    );
  }

  Future<List<LlbotApiResult>> sendMessages({
    required int userId,
    int? groupId,
    required List<String> messages,
  }) async {
    final results = <LlbotApiResult>[];
    for (final message in messages) {
      final text = message.trim();
      if (text.isEmpty) {
        continue;
      }
      results.add(
        groupId != null
            ? await sendGroup(groupId, text)
            : await sendPrivate(userId, text),
      );
    }
    return results;
  }

  Future<String?> getMessageText(int messageId) async {
    try {
      final body = await _post('get_msg', {'message_id': messageId});
      final data = body['data'];
      if (data is! Map) {
        return null;
      }
      final text = _extractMessageText(data['message']);
      if (text.isEmpty) {
        return null;
      }
      final deleted = data['status']?.toString() == 'deleted';
      final sender = data['sender'];
      var senderText = '';
      if (sender is Map) {
        final card = sender['card']?.toString().trim() ?? '';
        final nickname = sender['nickname']?.toString().trim() ?? '';
        final uid = sender['user_id']?.toString().trim() ?? '';
        final name = card.isNotEmpty ? card : nickname;
        senderText = name.isEmpty
            ? uid
            : (uid.isEmpty ? name : '$name($uid)');
      }
      final prefix = senderText.isEmpty ? '' : '$senderText: ';
      return '${deleted ? '[已撤回] ' : ''}$prefix$text';
    } catch (_) {
      return null;
    }
  }

  Future<String?> uploadPrivateFile({
    required int userId,
    required String file,
    String? name,
  }) async {
    try {
      final body = await _post('upload_private_file', {
        'user_id': userId,
        'file': file,
        if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
      });
      final data = body['data'];
      if (data is Map) {
        return data['file_id']?.toString() ?? 'ok';
      }
      return 'ok';
    } catch (_) {
      return null;
    }
  }

  Future<String?> uploadGroupFile({
    required int groupId,
    required String file,
    String? name,
  }) async {
    try {
      final body = await _post('upload_group_file', {
        'group_id': groupId,
        'file': file,
        if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
      });
      final data = body['data'];
      if (data is Map) {
        return data['file_id']?.toString() ?? 'ok';
      }
      return 'ok';
    } catch (_) {
      return null;
    }
  }

  Future<List<String>> fetchCustomFaces() async {
    try {
      final response = await _dio.get<dynamic>(
        '/fetch_custom_face',
        options: Options(responseType: ResponseType.bytes),
      );
      final map = _decodeMap(response.data);
      if (map == null) {
        return const [];
      }
      final retcode = _asInt(map['retcode']);
      if (retcode != null && retcode != 0) {
        return const [];
      }
      final data = map['data'];
      if (data is! List) {
        return const [];
      }
      return data
          .map((item) => item?.toString().trim() ?? '')
          .where((item) => item.isNotEmpty)
          .toList(growable: false);
    } catch (_) {
      return const [];
    }
  }

  Future<String?> getRecordBase64(String file) async {
    try {
      final body = await _post('get_record', {
        'file': file,
        'out_format': 'mp3',
      });
      final data = body['data'];
      if (data is! Map) {
        return null;
      }
      final base64 = data['base64']?.toString().trim() ?? '';
      return base64.isEmpty ? null : base64;
    } catch (_) {
      return null;
    }
  }

  Future<String?> voiceMsgToText(int messageId) async {
    try {
      final body = await _post('voice_msg_to_text', {
        'message_id': messageId,
      });
      final data = body['data'];
      if (data is! Map) {
        return null;
      }
      final text = data['text']?.toString().trim() ?? '';
      return text.isEmpty ? null : text;
    } catch (_) {
      return null;
    }
  }

  Future<bool> setFriendAddRequest({
    required String flag,
    required bool approve,
    String remark = '',
  }) async {
    try {
      await _post('set_friend_add_request', {
        'flag': flag,
        'approve': approve,
        'remark': remark,
      });
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<bool> deleteFriend(int userId) async {
    try {
      await _post('delete_friend', {'user_id': userId});
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<List<Map<String, dynamic>>> getFriendList() async {
    try {
      return _mapList(await _postData('get_friend_list', {}));
    } catch (_) {
      return const [];
    }
  }

  Future<List<Map<String, dynamic>>> getDoubtFriendRequests({
    int count = 50,
  }) async {
    try {
      return _mapList(
        await _postData('get_doubt_friends_add_request', {
          'count': count.clamp(1, 200),
        }),
      );
    } catch (_) {
      return const [];
    }
  }

  Future<bool> setDoubtFriendAddRequest(String flag) async {
    try {
      await _post('set_doubt_friends_add_request', {'flag': flag});
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<LlbotApiResult> _sendMessage(
    String action,
    Map<String, dynamic> body,
  ) async {
    try {
      final response = await _post(action, body);
      final retcode = _asInt(response['retcode']);
      if (retcode != null && retcode != 0) {
        return LlbotApiResult(
          ok: false,
          error: response['message']?.toString() ??
              response['wording']?.toString() ??
              'retcode=$retcode',
        );
      }
      final data = response['data'];
      final messageId = data is Map ? _asInt(data['message_id']) : null;
      return LlbotApiResult(ok: true, messageId: messageId);
    } on DioException catch (e) {
      return LlbotApiResult(ok: false, error: e.message ?? e.toString());
    } catch (e) {
      return LlbotApiResult(ok: false, error: e.toString());
    }
  }

  Future<Map<String, dynamic>> _post(
    String action,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post<dynamic>(
      '/$action',
      data: body,
      options: Options(responseType: ResponseType.bytes),
    );
    final data = _decodeMap(response.data);
    if (data == null) {
      throw StateError('LLBot 返回了非 JSON 内容');
    }
    return data;
  }

  Future<dynamic> _postData(
    String action,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post<dynamic>(
      '/$action',
      data: body,
      options: Options(responseType: ResponseType.bytes),
    );
    final map = _decodeMap(response.data);
    if (map == null) {
      throw StateError('LLBot 返回了非 JSON 内容');
    }
    final retcode = _asInt(map['retcode']);
    if (retcode != null && retcode != 0) {
      throw StateError(
        map['message']?.toString() ??
            map['wording']?.toString() ??
            'retcode=$retcode',
      );
    }
    return map['data'];
  }

  static Map<String, dynamic>? _decodeMap(Object? data) {
    if (data is Map<String, dynamic>) {
      return data;
    }
    if (data is Map) {
      return Map<String, dynamic>.from(data);
    }
    final text = switch (data) {
      List<int>() => const Utf8Decoder(allowMalformed: true).convert(data),
      String() => data,
      _ => null,
    };
    if (text == null || text.trim().isEmpty) {
      return null;
    }
    final decoded = jsonDecode(text);
    if (decoded is Map<String, dynamic>) {
      return decoded;
    }
    if (decoded is Map) {
      return Map<String, dynamic>.from(decoded);
    }
    return null;
  }

  static List<Map<String, dynamic>> _mapList(Object? data) {
    if (data is! List) {
      return const [];
    }
    return data
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList(growable: false);
  }

  static String _extractMessageText(Object? message) {
    if (message is String) {
      return message.trim();
    }
    if (message is! List) {
      return '';
    }
    final text = StringBuffer();
    for (final segment in message) {
      if (segment is! Map) {
        continue;
      }
      final type = segment['type']?.toString();
      final data = segment['data'];
      switch (type) {
        case 'text':
          if (data is Map) {
            text.write(data['text']?.toString() ?? '');
          }
        case 'image':
          text.write('[图片]');
        case 'face':
          text.write('[表情]');
        case 'record':
          text.write('[语音]');
      }
    }
    return text.toString().trim();
  }

  static int? _asInt(Object? value) {
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.toInt();
    }
    return int.tryParse(value?.toString() ?? '');
  }
}
