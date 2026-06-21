import 'dart:async';
import 'dart:io';

import '../radar/rssi_filter.dart';

/// 설계서 2.3 — Tiered Proximity.
///
/// BLE(Tier 1)는 "홀로그램 강조" 신호일 뿐, 노출의 전제 조건이 아니다.
/// iOS는 백그라운드에서 커스텀 데이터 BLE Advertising이 불가능하므로
/// (서비스 UUID가 overflow area로 밀림 — OS 정책, 라이브러리로 우회 불가)
/// iOS는 포그라운드 한정으로 광고하고, 노출 자체는 서버 Presence(Tier 2)가 보장한다.
class BleProximityService {
  BleProximityService({required this.onTokensChanged});

  /// "가시거리 안" 판정이 바뀔 때마다 현재 활성 토큰 집합을 통지.
  /// 호출 측은 이 집합을 POST /radar/resolve 로 보낸다 (쿨타임 3분).
  final void Function(Set<String> activeTokens) onTokensChanged;

  final Map<String, RssiHysteresisFilter> _filters = {};
  String? _myToken;
  Timer? _timeoutTimer;

  bool get advertisingSupported {
    // Android: Foreground Service로 백그라운드 광고 가능.
    // iOS: 포그라운드에서만 서비스 UUID 광고 가능 (manufacturer data 불가).
    return Platform.isAndroid || Platform.isIOS;
  }

  /// 방 입장/토큰 리프레시 시 호출. 10분 주기 — 서버 TTL과 동기화하되
  /// Android BLE MAC 랜덤화 주기(약 15분)와 어긋나게 유지한다 (설계서 2.2).
  Future<void> startAdvertising(String myBleToken) async {
    _myToken = myBleToken;
    // TODO(1주차 Spike): flutter_blue_plus 또는 플랫폼 채널로
    //  - Android: Foreground Service + AdvertiseData(serviceUuid=FSNS_UUID, serviceData=token)
    //  - iOS: CBPeripheralManager.startAdvertising (포그라운드 한정)
    // 1주차 검증 리포트에서 실패 판정 시 Tier 2 단독 모델로 피벗 (설계서 6).
  }

  Future<void> startScanning() async {
    _timeoutTimer ??= Timer.periodic(const Duration(seconds: 10), (_) => _sweepTimeouts());
    // TODO(1주차 Spike): FSNS 서비스 UUID 필터로 스캔, 발견 시 onAdvertisement() 호출.
  }

  /// 스캔 콜백 — 토큰별 RSSI 샘플을 히스테리시스 필터에 통과시킨다.
  void onAdvertisement(String token, int rssiDbm) {
    if (token == _myToken) return;
    final filter = _filters.putIfAbsent(token, RssiHysteresisFilter.new);
    final wasActive = filter.isActive;
    final nowActive = filter.addSample(rssiDbm);
    if (wasActive != nowActive) {
      onTokensChanged(activeTokens);
    }
  }

  Set<String> get activeTokens => _filters.entries
      .where((e) => e.value.isActive)
      .map((e) => e.key)
      .toSet();

  void _sweepTimeouts() {
    var changed = false;
    for (final entry in _filters.entries) {
      final wasActive = entry.value.isActive;
      if (wasActive != entry.value.checkTimeout()) changed = true;
    }
    if (changed) onTokensChanged(activeTokens);
  }

  /// 토큰 리프레시 시 기존 필터 상태는 유지하되 내 토큰만 교체.
  void updateMyToken(String newToken) => _myToken = newToken;

  void dispose() {
    _timeoutTimer?.cancel();
    _filters.clear();
  }
}
