import 'package:fashion_radar/radar/rssi_filter.dart';
import 'package:flutter_test/flutter_test.dart';

/// 설계서 2.4 검증 — 이동 중앙값 + 히스테리시스 이진 판정.
void main() {
  group('RssiHysteresisFilter', () {
    test('강한 신호(≥ -80dBm) 2회 연속이어야 활성 전환', () {
      final f = RssiHysteresisFilter();
      expect(f.addSample(-70), isFalse); // 1회째 — 아직
      expect(f.addSample(-70), isTrue);  // 2회 연속 — 활성
    });

    test('강한 신호 1회만으로는 활성되지 않음 (스파이크 방어)', () {
      final f = RssiHysteresisFilter();
      f.addSample(-70);
      expect(f.addSample(-95), isFalse); // 중앙값이 다시 낮아짐 — streak 끊김
      expect(f.isActive, isFalse);
    });

    test('경계 신호(-85dBm)에서 플리커 없음 — 히스테리시스 밴드', () {
      final f = RssiHysteresisFilter();
      // 먼저 확실히 활성화
      f.addSample(-70);
      f.addSample(-70);
      expect(f.isActive, isTrue);
      // -80 ~ -90 사이 경계값이 계속 들어와도 활성 유지 (깜빡임 없음)
      for (var i = 0; i < 20; i++) {
        f.addSample(-85);
      }
      expect(f.isActive, isTrue);
    });

    test('약한 신호(< -90dBm) 3회 연속이어야 비활성 전환', () {
      final f = RssiHysteresisFilter(windowSize: 3);
      f.addSample(-70);
      f.addSample(-70);
      expect(f.isActive, isTrue);
      // 윈도우를 약한 신호로 채워 중앙값을 -95로 끌어내린 뒤 streak 검증
      f.addSample(-95); // 중앙값 -70 → streak 0
      f.addSample(-95); // 중앙값 -95 → streak 1
      f.addSample(-95); // streak 2
      expect(f.isActive, isTrue);
      f.addSample(-95); // streak 3 — 비활성
      expect(f.isActive, isFalse);
    });

    test('이동 중앙값이 단발 스파이크를 걸러냄', () {
      final f = RssiHysteresisFilter();
      f.addSample(-95);
      f.addSample(-95);
      f.addSample(-95);
      // 멀티패스로 인한 순간 스파이크 — 중앙값은 여전히 -95
      f.addSample(-60);
      expect(f.isActive, isFalse);
    });

    test('활성/비활성 임계값이 분리되어 있음 (-80 진입 / -90 이탈)', () {
      final f = RssiHysteresisFilter();
      expect(f.enterThresholdDbm, greaterThan(f.exitThresholdDbm));
    });
  });
}
