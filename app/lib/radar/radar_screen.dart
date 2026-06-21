import 'dart:async';

import 'package:flutter/material.dart';

import '../ble/ble_service.dart';
import '../core/api_client.dart';

/// 가시거리 레이더 화면.
///
/// Tier 2(서버 Presence) 갤러리를 기본으로 깔고, Tier 1(BLE) 매칭 시
/// 해당 아바타에 홀로그램 테두리 강조를 입힌다 (설계서 2.3 UX 규칙).
class RadarScreen extends StatefulWidget {
  const RadarScreen({super.key, required this.api});

  final ApiClient api;

  @override
  State<RadarScreen> createState() => _RadarScreenState();
}

class _RadarScreenState extends State<RadarScreen> {
  late final BleProximityService _ble;
  Timer? _tokenRefreshTimer;

  String _roomName = '';
  List<Map<String, dynamic>> _gallery = [];
  Set<String> _highlighted = {}; // session_avatar_id 집합 (Tier 1 강조)
  DateTime _lastResolve = DateTime.fromMillisecondsSinceEpoch(0);

  @override
  void initState() {
    super.initState();
    _ble = BleProximityService(onTokensChanged: _onActiveTokensChanged);
    _enterRoom();
  }

  Future<void> _enterRoom() async {
    // 실제로는 OS 지오펜스 경계 이벤트에서만 호출 (설계서 2.5 — 상시 폴링 금지)
    final room = await widget.api.enterRoom(37.5445, 127.0561);
    setState(() {
      _roomName = room['room_name'] as String;
      _gallery = (room['gallery'] as List).cast<Map<String, dynamic>>();
    });
    await _ble.startAdvertising(room['my_ble_token'] as String);
    await _ble.startScanning();
    _scheduleTokenRefresh(room['token_expires_in_sec'] as int);
  }

  /// 토큰 10분 주기 리프레시 — MAC 랜덤화(약 15분)와 비동기화 (설계서 2.2)
  void _scheduleTokenRefresh(int expiresInSec) {
    _tokenRefreshTimer?.cancel();
    _tokenRefreshTimer = Timer(Duration(seconds: expiresInSec - 30), () async {
      final refreshed = await widget.api.refreshBleToken();
      _ble.updateMyToken(refreshed['my_ble_token'] as String);
      _scheduleTokenRefresh(refreshed['token_expires_in_sec'] as int);
    });
  }

  /// BLE 가시거리 판정 변화 → 서버 resolve (쿨타임 3분 준수)
  Future<void> _onActiveTokensChanged(Set<String> tokens) async {
    if (tokens.isEmpty) {
      setState(() => _highlighted = {});
      return;
    }
    if (DateTime.now().difference(_lastResolve) < const Duration(minutes: 3)) {
      return; // 서버도 거부하지만 클라이언트가 먼저 아낀다
    }
    _lastResolve = DateTime.now();
    final result = await widget.api.resolveRadar(tokens);
    setState(() {
      _highlighted = (result['nearby'] as List)
          .cast<Map<String, dynamic>>()
          .where((n) => n['highlight'] == true)
          .map((n) => n['session_avatar_id'] as String)
          .toSet();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_roomName.isEmpty ? '레이더' : _roomName)),
      body: GridView.builder(
        padding: const EdgeInsets.all(16),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2, mainAxisSpacing: 16, crossAxisSpacing: 16),
        itemCount: _gallery.length,
        itemBuilder: (context, index) {
          final card = _gallery[index];
          final saId = card['session_avatar_id'] as String;
          final isNear = _highlighted.contains(saId);
          return _AvatarCard(
            styleSummary: card['today_style_summary'] as String? ?? '',
            highlighted: isNear,
          );
        },
      ),
    );
  }

  @override
  void dispose() {
    _tokenRefreshTimer?.cancel();
    _ble.dispose();
    super.dispose();
  }
}

class _AvatarCard extends StatelessWidget {
  const _AvatarCard({required this.styleSummary, required this.highlighted});

  final String styleSummary;
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 400),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        border: highlighted
            // Tier 1: 홀로그램 테두리 강조
            ? Border.all(color: Colors.cyanAccent, width: 3)
            : Border.all(color: Colors.transparent, width: 3),
        boxShadow: highlighted
            ? [BoxShadow(color: Colors.cyanAccent.withOpacity(0.5), blurRadius: 12)]
            : const [],
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.person_outline, size: 64),
          const SizedBox(height: 8),
          Text(styleSummary, textAlign: TextAlign.center),
          if (highlighted)
            const Padding(
              padding: EdgeInsets.only(top: 4),
              child: Text('가시거리 안', style: TextStyle(color: Colors.cyan, fontSize: 12)),
            ),
        ],
      ),
    );
  }
}
