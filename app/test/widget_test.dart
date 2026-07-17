import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:fashion_radar/auth/login_screen.dart';
import 'package:fashion_radar/core/api_client.dart';

void main() {
  testWidgets('로그인 화면 — 구글 로그인 버튼이 노출된다', (WidgetTester tester) async {
    await tester.pumpWidget(
        MaterialApp(home: LoginScreen(api: ApiClient())));

    expect(find.text('Fashion-Radar'), findsOneWidget);
    expect(find.text('Google로 계속하기'), findsOneWidget);
    // 디버그 빌드(테스트 포함)에서만 dev-login 우회 버튼이 보인다
    expect(find.text('개발자 로그인 (dev-login)'), findsOneWidget);
  });
}
