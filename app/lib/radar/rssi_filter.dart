/// 설계서 2.4 — RSSI 처리: 거리 계산 폐기, 이진 판정 + 필터링.
///
/// 거리를 미터로 환산하지 않는다. 환산값을 UI에 쓰는 순간 "3m 거리에 있음"
/// 같은 방향·거리 추적 단서를 다시 제공하게 되기 때문이다.
/// 시스템은 "가시거리 안/밖" 1비트 정보만 다룬다.
class RssiHysteresisFilter {
  RssiHysteresisFilter({
    this.windowSize = 7, // 슬라이딩 윈도우 5~10개 샘플
    this.enterThresholdDbm = -80,
    this.exitThresholdDbm = -90,
    this.enterConsecutive = 2, // 비활성 → 활성: ≥ -80dBm 2회 연속
    this.exitConsecutive = 3, //  활성 → 비활성: < -90dBm 3회 연속
    this.signalTimeout = const Duration(seconds: 60),
  });

  final int windowSize;
  final int enterThresholdDbm;
  final int exitThresholdDbm;
  final int enterConsecutive;
  final int exitConsecutive;
  final Duration signalTimeout;

  final List<int> _window = [];
  int _enterStreak = 0;
  int _exitStreak = 0;
  DateTime _lastSample = DateTime.now();
  bool _active = false;

  /// 현재 "가시거리 안" 판정 여부 (홀로그램 강조 ON/OFF).
  bool get isActive => _active;

  /// RSSI 샘플 1개를 반영하고 갱신된 판정을 돌려준다.
  bool addSample(int rssiDbm) {
    _lastSample = DateTime.now();

    // 1) 이동 중앙값(median) — 멀티패스·인체 차폐로 인한 스파이크 제거
    _window.add(rssiDbm);
    if (_window.length > windowSize) _window.removeAt(0);
    final median = _median(_window);

    // 2) 히스테리시스 이진 판정 — ON/OFF 임계값을 분리해
    //    경계 RSSI에서 테두리가 깜빡이는 플리커를 막는다
    if (!_active) {
      _enterStreak = median >= enterThresholdDbm ? _enterStreak + 1 : 0;
      if (_enterStreak >= enterConsecutive) {
        _active = true;
        _enterStreak = 0;
      }
    } else {
      _exitStreak = median < exitThresholdDbm ? _exitStreak + 1 : 0;
      if (_exitStreak >= exitConsecutive) {
        _active = false;
        _exitStreak = 0;
      }
    }
    return _active;
  }

  /// 60초 무신호 시 비활성 처리. 주기 타이머에서 호출할 것.
  bool checkTimeout() {
    if (_active && DateTime.now().difference(_lastSample) > signalTimeout) {
      _active = false;
      _window.clear();
    }
    return _active;
  }

  static int _median(List<int> samples) {
    final sorted = [...samples]..sort();
    return sorted[sorted.length ~/ 2];
  }
}
