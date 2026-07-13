import 'package:flutter/material.dart';

import 'core/api_client.dart';
import 'home/home_shell.dart';

void main() {
  runApp(const FashionRadarApp());
}

class FashionRadarApp extends StatelessWidget {
  const FashionRadarApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Fashion-Radar',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.cyan, brightness: Brightness.dark),
        useMaterial3: true,
      ),
      home: const _Bootstrap(),
    );
  }
}

/// 개발용 부트스트랩: dev-login 후 레이더 진입.
/// 카카오/구글 OAuth 연동 시 이 화면을 소셜 로그인 화면으로 교체한다.
class _Bootstrap extends StatefulWidget {
  const _Bootstrap();

  @override
  State<_Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<_Bootstrap> {
  final _api = ApiClient();
  String? _error;

  @override
  void initState() {
    super.initState();
    _login();
  }

  Future<void> _login() async {
    try {
      await _api.devLogin('dev@fsns.app', '개발자', DateTime(2000, 1, 1));
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => HomeShell(api: _api)));
    } on Exception catch (e) {
      setState(() => _error = e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: _error == null
            ? const CircularProgressIndicator()
            : Text('로그인 실패: $_error'),
      ),
    );
  }
}
