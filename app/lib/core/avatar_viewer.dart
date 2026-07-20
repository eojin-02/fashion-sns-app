import 'package:flutter/material.dart';
import 'package:model_viewer_plus/model_viewer_plus.dart';

/// 3D 아바타 뷰어 (설계서 4.2 avatar_bundle_url).
///
/// AI 워커가 옷장 태그로 생성한 블록 캐릭터 GLB를 로드한다.
/// 드래그로 앞뒤 양옆 360° 회전이 가능하고, 가만히 두면 자동 회전한다.
class AvatarViewer extends StatelessWidget {
  const AvatarViewer({super.key, required this.bundleUrl, this.height = 260});

  final String? bundleUrl;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (bundleUrl == null || bundleUrl!.isEmpty) {
      return SizedBox(
        height: height,
        child: const Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.person_outline, size: 48),
              SizedBox(height: 8),
              Text('옷을 등록하면 3D 아바타가 생성됩니다'),
            ],
          ),
        ),
      );
    }
    return SizedBox(
      height: height,
      child: ModelViewer(
        // WebView 기반이라 src가 바뀌어도 자동 리로드되지 않는다 —
        // URL을 key로 걸어 새로고침(새 presigned URL)마다 위젯을 재생성해 반영.
        key: ValueKey(bundleUrl),
        src: bundleUrl!,
        cameraControls: true, // 드래그 회전 — 앞뒤 양옆 전부
        autoRotate: true,
        autoRotateDelay: 1000,
        disableZoom: false,
        backgroundColor: Colors.transparent,
      ),
    );
  }
}
