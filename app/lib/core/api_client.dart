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

  /// 설계서 4.0 — 구글 ID 토큰 → 자체 JWT.
  /// 신규 유저면 서버가 {signup_required: true}를 반환하며, 이때는 토큰이
  /// 저장되지 않는다. 닉네임/생년월일을 받아 같은 ID 토큰으로 재호출해 가입한다.
  Future<Map<String, dynamic>> loginWithGoogle(String idToken,
      {String? nickname, DateTime? birthDate}) async {
    final body = await _post('/api/v1/auth/oauth/google', {
      'id_token': idToken,
      if (nickname != null) 'nickname': nickname,
      if (birthDate != null)
        'birth_date': birthDate.toIso8601String().substring(0, 10),
    });
    if (body['signup_required'] != true) {
      _accessToken = body['access_token'] as String;
      _refreshToken = body['refresh_token'] as String;
    }
    return body;
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

  /// productUrl(선택): 쇼핑몰 상품 페이지 — 워커가 상품컷/상품명으로 태깅을 강화한다.
  Future<Map<String, dynamic>> requestScan(String imageKey,
          {String? productUrl}) =>
      _post('/api/v1/wardrobe/scan', {
        'image_key': imageKey,
        if (productUrl != null && productUrl.isNotEmpty)
          'product_url': productUrl,
      });

  /// 오늘의 코디 — 선택한 옷장 아이템 조합을 아바타에 입힌다 (설계서 4.2).
  /// 저장 즉시 서버가 아바타 재생성 잡을 큐에 넣고, 완료는 WebSocket으로 통지된다.
  Future<Map<String, dynamic>> setCodi(List<int> itemIds) =>
      _send('PUT', '/api/v1/codi', {'item_ids': itemIds});

  Future<Map<String, dynamic>> getCodi() => _get('/api/v1/codi');

  Future<Map<String, dynamic>> getMe() => _get('/api/v1/users/me');

  Future<Map<String, dynamic>> setGhostMode({required bool visible}) =>
      _patch('/api/v1/users/me/visibility', {'radar_visible': visible});

  /// 아바타 베이스 파라미터(피부/헤어) 변경 — 저장 즉시 서버가 재생성 잡을 큐에 넣는다.
  Future<Map<String, dynamic>> updateAvatarConfig(Map<String, String> config) =>
      _patch('/api/v1/users/me/avatar-config', config);

  /// 옷장 — 내 아이템 / 찜 목록
  Future<List<Map<String, dynamic>>> getMyItems() =>
      _getList('/api/v1/wardrobe/items');

  Future<List<Map<String, dynamic>>> getMyLikes() =>
      _getList('/api/v1/wardrobe/likes');

  Future<void> likeItem(int itemId) async =>
      _post('/api/v1/wardrobe/items/$itemId/like', {});

  Future<void> unlikeItem(int itemId) async =>
      _send('DELETE', '/api/v1/wardrobe/items/$itemId/like', {});

  /// 아바타 스코프 상호작용 — 타 유저의 user_id는 클라이언트에 존재하지 않는다.
  /// 갤러리/레이더의 session_avatar_id 하나로 프로필·차단·신고 전부 처리 (설계서 2.2)
  Future<Map<String, dynamic>> getAvatarProfile(String saId) =>
      _get('/api/v1/avatars/$saId');

  /// 코디 반응 — 좋아요는 익명 카운트만, 댓글은 닉네임 표시까지만 (프로필 연결 없음)
  Future<void> likeCodi(String saId) async =>
      _post('/api/v1/avatars/$saId/codi/like', {});

  Future<void> unlikeCodi(String saId) async =>
      _send('DELETE', '/api/v1/avatars/$saId/codi/like', {});

  Future<List<Map<String, dynamic>>> getCodiComments(String saId) =>
      _getList('/api/v1/avatars/$saId/codi/comments');

  Future<Map<String, dynamic>> postCodiComment(String saId, String content) =>
      _post('/api/v1/avatars/$saId/codi/comments', {'content': content});

  Future<void> deleteCodiComment(int commentId) async =>
      _send('DELETE', '/api/v1/codi/comments/$commentId', {});

  Future<void> reportCodiComment(int commentId) async =>
      _post('/api/v1/codi/comments/$commentId/report', {});

  Future<void> blockAvatar(String saId) async =>
      _post('/api/v1/avatars/$saId/block', {});

  Future<void> reportAvatar(String saId, {String? reason}) async =>
      _post('/api/v1/avatars/$saId/report', {'reason': reason});

  Future<Map<String, dynamic>> _get(String path) => _send('GET', path, null);

  Future<List<Map<String, dynamic>>> _getList(String path) async {
    final response = await http.get(Uri.parse('$baseUrl$path'), headers: _headers);
    if (response.statusCode >= 300) {
      throw ApiException(response.statusCode, '요청 실패');
    }
    return (jsonDecode(utf8.decode(response.bodyBytes)) as List)
        .cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> _post(String path, Map<String, dynamic> body) =>
      _send('POST', path, body);

  Future<Map<String, dynamic>> _patch(String path, Map<String, dynamic> body) =>
      _send('PATCH', path, body);

  Future<Map<String, dynamic>> _send(
      String method, String path, Map<String, dynamic>? body) async {
    final request = http.Request(method, Uri.parse('$baseUrl$path'))
      ..headers.addAll(_headers);
    if (body != null) {
      request.body = jsonEncode(body);
    }
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
