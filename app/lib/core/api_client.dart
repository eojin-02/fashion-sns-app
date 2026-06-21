import 'dart:convert';

import 'package:http/http.dart' as http;

/// Fashion-Radar API v2.0 클라이언트 (설계서 4장).
class ApiClient {
  ApiClient({this.baseUrl = 'http://10.0.2.2:8080'}); // Android 에뮬레이터 → 호스트

  final String baseUrl;
  String? _accessToken;
  String? _refreshToken;

  bool get isAuthenticated => _accessToken != null;

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (_accessToken != null) 'Authorization': 'Bearer $_accessToken',
      };

  Future<void> devLogin(String email, String nickname, DateTime birthDate) async {
    final body = await _post('/api/v1/auth/dev-login', {
      'email': email,
      'nickname': nickname,
      'birth_date': birthDate.toIso8601String().substring(0, 10),
    });
    _accessToken = body['access_token'] as String;
    _refreshToken = body['refresh_token'] as String;
  }

  Future<void> refreshAuth() async {
    final body = await _post('/api/v1/auth/refresh', {'refresh_token': _refreshToken});
    _accessToken = body['access_token'] as String;
    _refreshToken = body['refresh_token'] as String;
  }

  /// 설계서 4.2 — 응답에는 내 토큰만 온다. 타인 토큰 목록은 존재하지 않는다.
  Future<Map<String, dynamic>> enterRoom(double latitude, double longitude) =>
      _post('/api/v1/location/room/enter', {'latitude': latitude, 'longitude': longitude});

  /// 설계서 4.3 — 쿨타임 3분. 429 응답은 호출 측에서 백오프 처리.
  Future<Map<String, dynamic>> resolveRadar(Set<String> scannedTokens) =>
      _post('/api/v1/radar/resolve', {'scanned_tokens': scannedTokens.toList()});

  /// 설계서 4.4 — 10분 주기 토큰 리프레시.
  Future<Map<String, dynamic>> refreshBleToken() =>
      _post('/api/v1/radar/token/refresh', {});

  /// 설계서 4.1 — Presigned 업로드 → 비동기 스캔.
  Future<Map<String, dynamic>> createUploadUrl() =>
      _post('/api/v1/wardrobe/upload-url', {});

  Future<void> uploadImage(String presignedUrl, List<int> jpegBytes) async {
    final res = await http.put(Uri.parse(presignedUrl),
        headers: {'Content-Type': 'image/jpeg'}, body: jpegBytes);
    if (res.statusCode >= 300) {
      throw ApiException(res.statusCode, '이미지 업로드 실패');
    }
  }

  Future<Map<String, dynamic>> requestScan(String imageKey) =>
      _post('/api/v1/wardrobe/scan', {'image_key': imageKey});

  Future<Map<String, dynamic>> setGhostMode({required bool visible}) =>
      _patch('/api/v1/users/me/visibility', {'radar_visible': visible});

  Future<void> blockUser(int userId) async =>
      _post('/api/v1/users/$userId/block', {});

  Future<Map<String, dynamic>> _post(String path, Map<String, dynamic> body) =>
      _send('POST', path, body);

  Future<Map<String, dynamic>> _patch(String path, Map<String, dynamic> body) =>
      _send('PATCH', path, body);

  Future<Map<String, dynamic>> _send(
      String method, String path, Map<String, dynamic> body) async {
    final request = http.Request(method, Uri.parse('$baseUrl$path'))
      ..headers.addAll(_headers)
      ..body = jsonEncode(body);
    final response = await http.Response.fromStream(await request.send());
    final decoded = response.body.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
    if (response.statusCode >= 300) {
      throw ApiException(
          response.statusCode, decoded['error'] as String? ?? '요청 실패');
    }
    return decoded;
  }
}

class ApiException implements Exception {
  ApiException(this.statusCode, this.message);

  final int statusCode;
  final String message;

  @override
  String toString() => 'ApiException($statusCode): $message';
}
