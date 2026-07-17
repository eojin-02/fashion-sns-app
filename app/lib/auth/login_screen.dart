import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../core/api_client.dart';
import '../home/home_shell.dart';
import '../profile/avatar_editor.dart';

/// 구글 로그인 화면 (설계서 4.0).
///
/// 플로우: 구글 인증 → ID 토큰을 서버로 → 기존 유저면 즉시 자체 JWT 수령,
/// 신규 유저면 signup_required → 닉네임/생년월일(만 14세 검증용) 입력 후
/// 같은 토큰으로 재호출해 가입. 발급받은 JWT 이후의 흐름은 dev-login과 동일하다.
///
/// serverClientId(구글 콘솔 "웹 애플리케이션" 클라이언트 ID)는 빌드 시
/// --dart-define=GOOGLE_SERVER_CLIENT_ID=... 로 주입하며, 서버의
/// APP_GOOGLE_CLIENT_ID와 같은 값이어야 한다.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.api});

  final ApiClient api;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  static const _serverClientId =
      String.fromEnvironment('GOOGLE_SERVER_CLIENT_ID');
  static Future<void>? _googleInit; // initialize()는 프로세스당 1회

  bool _busy = false;

  Future<void> _ensureGoogleInitialized() =>
      _googleInit ??= GoogleSignIn.instance.initialize(
          serverClientId: _serverClientId.isEmpty ? null : _serverClientId);

  Future<void> _googleLogin() async {
    setState(() => _busy = true);
    try {
      await _ensureGoogleInitialized();
      final account = await GoogleSignIn.instance.authenticate();
      final idToken = account.authentication.idToken;
      if (idToken == null) {
        _snack('구글 ID 토큰을 받지 못했습니다. GOOGLE_SERVER_CLIENT_ID 설정을 확인하세요.');
        return;
      }

      var body = await widget.api.loginWithGoogle(idToken);
      if (body['signup_required'] == true) {
        final profile = await _askProfile(
            suggestedNickname: (body['suggested_nickname'] as String?) ??
                account.displayName ??
                '');
        if (profile == null) return; // 가입 취소
        body = await widget.api.loginWithGoogle(idToken,
            nickname: profile.nickname, birthDate: profile.birthDate);
        // 가입 직후 1회 — 아바타 커스터마이징 (건너뛰면 기본 아바타 유지)
        if (mounted) {
          await Navigator.of(context).push(MaterialPageRoute(
              builder: (_) =>
                  AvatarEditorScreen(api: widget.api, isSignup: true)));
        }
      }
      _goHome();
    } on GoogleSignInException catch (e) {
      if (e.code != GoogleSignInExceptionCode.canceled) {
        _snack('구글 로그인 실패: ${e.description ?? e.code.name}');
      }
    } on ApiException catch (e) {
      _snack(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 신규 가입 — 닉네임과 생년월일 입력 (생년월일은 만 14세 검증용, 서버가 최종 검증)
  Future<({String nickname, DateTime birthDate})?> _askProfile(
      {required String suggestedNickname}) {
    final nicknameCtrl = TextEditingController(text: suggestedNickname);
    DateTime? birthDate;
    return showDialog<({String nickname, DateTime birthDate})>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('프로필 설정'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: nicknameCtrl,
                maxLength: 30,
                decoration: const InputDecoration(labelText: '닉네임'),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                icon: const Icon(Icons.cake),
                label: Text(birthDate == null
                    ? '생년월일 선택'
                    : birthDate!.toIso8601String().substring(0, 10)),
                onPressed: () async {
                  final now = DateTime.now();
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: DateTime(now.year - 20),
                    firstDate: DateTime(1900),
                    lastDate: now,
                  );
                  if (picked != null) {
                    setDialogState(() => birthDate = picked);
                  }
                },
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () {
                final nickname = nicknameCtrl.text.trim();
                if (nickname.isEmpty || birthDate == null) return;
                Navigator.pop(
                    context, (nickname: nickname, birthDate: birthDate!));
              },
              child: const Text('가입'),
            ),
          ],
        ),
      ),
    );
  }

  /// 소셜 설정 없는 로컬 개발용 — 릴리스 빌드에는 노출되지 않는다
  Future<void> _devLogin() async {
    setState(() => _busy = true);
    try {
      await widget.api.devLogin('dev@fsns.app', '개발자', DateTime(2000, 1, 1));
      _goHome();
    } on Exception catch (e) {
      _snack('로그인 실패: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _goHome() {
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => HomeShell(api: widget.api)));
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.radar, size: 72),
            const SizedBox(height: 12),
            Text('Fashion-Radar',
                style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 48),
            if (_busy)
              const CircularProgressIndicator()
            else ...[
              FilledButton.icon(
                icon: const Icon(Icons.login),
                label: const Text('Google로 계속하기'),
                onPressed: _googleLogin,
              ),
              if (kDebugMode)
                TextButton(
                  onPressed: _devLogin,
                  child: const Text('개발자 로그인 (dev-login)'),
                ),
            ],
          ],
        ),
      ),
    );
  }
}
