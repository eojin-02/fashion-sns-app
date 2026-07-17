import 'package:flutter/material.dart';

import 'auth/login_screen.dart';
import 'core/api_client.dart';

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
      home: LoginScreen(api: ApiClient()),
    );
  }
}
