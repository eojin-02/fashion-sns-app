import 'package:flutter/material.dart';

import '../core/api_client.dart';
import '../profile/my_page.dart';
import '../radar/radar_screen.dart';
import '../wardrobe/wardrobe_screen.dart';

/// 앱 셸 — 하단 탭: 레이더 / 옷장 / 마이.
/// IndexedStack으로 탭 전환 시에도 각 화면의 상태(BLE 스캔, 목록)를 유지한다.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.api});

  final ApiClient api;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: [
          RadarScreen(api: widget.api),
          WardrobeScreen(api: widget.api),
          MyPage(api: widget.api),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.radar), label: '레이더'),
          NavigationDestination(icon: Icon(Icons.checkroom), label: '옷장'),
          NavigationDestination(icon: Icon(Icons.person), label: '마이'),
        ],
      ),
    );
  }
}
