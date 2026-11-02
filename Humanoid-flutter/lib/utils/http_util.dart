import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

class HttpUtil {
  HttpUtil._() {
    _dio = Dio(
      BaseOptions(
        baseUrl: defaultBaseUrl,
        connectTimeout: const Duration(seconds: 10),
        sendTimeout: const Duration(seconds: 30),
        receiveTimeout: const Duration(seconds: 180),
        responseType: ResponseType.json,
      ),
    );

    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          final token = _token;
          if (token != null && token.isNotEmpty) {
            options.headers['Authorization'] = 'Bearer $token';
          }
          handler.next(options);
        },
      ),
    );

    if (kDebugMode) {
      _dio.interceptors.add(
        LogInterceptor(requestBody: true, responseBody: true),
      );
    }
  }

  static final HttpUtil instance = HttpUtil._();

  static const defaultBaseUrl = 'http://127.0.0.1:8080';

  late final Dio _dio;
  String? _token;

  Dio get dio => _dio;

  String get baseUrl => _dio.options.baseUrl;

  void init({String? baseUrl, String? token}) {
    if (baseUrl != null && baseUrl.trim().isNotEmpty) {
      _dio.options.baseUrl = normalizeBaseUrl(baseUrl);
    }
    _token = token;
  }

  void setBaseUrl(String baseUrl) {
    _dio.options.baseUrl = normalizeBaseUrl(baseUrl);
  }

  void setToken(String? token) {
    _token = token;
  }

  Future<Response<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    Options? options,
  }) {
    return _dio.get<T>(path, queryParameters: query, options: options);
  }

  Future<Response<T>> post<T>(
    String path, {
    Object? data,
    Map<String, dynamic>? query,
    Options? options,
  }) {
    return _dio.post<T>(path, data: data, queryParameters: query, options: options);
  }

  Future<Response<T>> put<T>(
    String path, {
    Object? data,
    Map<String, dynamic>? query,
    Options? options,
  }) {
    return _dio.put<T>(path, data: data, queryParameters: query, options: options);
  }

  Future<Response<T>> patch<T>(
    String path, {
    Object? data,
    Map<String, dynamic>? query,
    Options? options,
  }) {
    return _dio.patch<T>(path, data: data, queryParameters: query, options: options);
  }

  Future<Response<T>> delete<T>(
    String path, {
    Object? data,
    Map<String, dynamic>? query,
    Options? options,
  }) {
    return _dio.delete<T>(path, data: data, queryParameters: query, options: options);
  }

  static String normalizeBaseUrl(String value) {
    final trimmed = value.trim();
    return trimmed.endsWith('/')
        ? trimmed.substring(0, trimmed.length - 1)
        : trimmed;
  }
}
