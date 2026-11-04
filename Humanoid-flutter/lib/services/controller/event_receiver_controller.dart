import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';

class EventReceiverController {
  EventReceiverController._();
  static final EventReceiverController instance = EventReceiverController._();
  HttpServer? _server;

  /// 启动监听 13001 端口
  Future<void> startServer() async {
    if (_server != null) return;

    try {
      // 关键：先赋值给一个非空的局部变量
      final server = await HttpServer.bind(InternetAddress.anyIPv4, 13001,shared: true);
      _server = server; // 再赋给成员变量

      debugPrint('✅ [Listener] HTTP 服务已启动，监听端口: 13001');

      // 用局部变量去循环，绝不报错
      await for (HttpRequest request in server) {
        _handleRequest(request);
      }
    } catch (e) {
      debugPrint('❌ [Listener] 启动失败: $e');
    }
  }

  /// 处理收到的请求
  void _handleRequest(HttpRequest request) async {
    // 设置 CORS，允许跨域（LLOneBot 上报时通常不需要，但为了保险加上）
    request.response.headers.add('Access-Control-Allow-Origin', '*');
    request.response.headers.add('Access-Control-Allow-Methods', 'POST, GET, OPTIONS');

    if (request.method == 'OPTIONS') {
      request.response.statusCode = HttpStatus.ok;
      await request.response.close();
      return;
    }

    if (request.method == 'POST') {
      // 读取请求体
      final content = await const Utf8Decoder(
        allowMalformed: true,
      ).bind(request).join();

      try {
        // 解析 JSON
        final data = jsonDecode(content);
        debugPrint('📩 [LLOneBot 上报] 收到消息: $data');

        // --- 在这里处理你的 AI 逻辑、保存记录等 ---
        // 比如：判断是不是文本消息，然后转给大模型

        // 返回 200 OK 告诉 LLOneBot 接收成功
        request.response.statusCode = HttpStatus.ok;
        request.response.write('{"status":"ok"}');
      } catch (e) {
        debugPrint('❌ [Listener] 解析数据失败: $e');
        request.response.statusCode = HttpStatus.badRequest;
      }
    } else {
      request.response.statusCode = HttpStatus.methodNotAllowed;
    }

    await request.response.close();
  }

  /// 停止服务
  Future<void> stopServer() async {
    await _server?.close(force: true);
    _server = null;
    debugPrint('🛑 [Listener] HTTP 服务已停止');
  }
}