import 'package:flutter/material.dart';

import '../core/api_client.dart';
import '../core/avatar_viewer.dart';
import 'avatar_editor.dart';

/// 마이페이지 — 고스트 모드 토글(설계서 1.2 핵심)과 찜 모아보기.
class MyPage extends StatefulWidget {
  const MyPage({super.key, required this.api});

  final ApiClient api;

  @override
  State<MyPage> createState() => _MyPageState();
}

class _MyPageState extends State<MyPage> {
  String _nickname = '';
  String? _avatarBundleUrl; // 내 3D 아바타 GLB — 옷 등록 전이면 null
  bool _radarVisible = false; // 기본 비노출(opt-in) — 서버 기본값과 동일
  bool _busy = false;
  List<Map<String, dynamic>> _likes = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final me = await widget.api.getMe();
      final likes = await widget.api.getMyLikes();
      if (mounted) {
        setState(() {
          _nickname = me['nickname'] as String? ?? '';
          _avatarBundleUrl = me['avatar_bundle_url'] as String?;
          _radarVisible = me['radar_visible'] as bool? ?? false;
          _likes = likes;
        });
      }
    } on ApiException {
      // 마이페이지는 조용히 실패 허용 — 다음 진입 시 재시도
    }
  }

  Future<void> _toggleVisibility(bool value) async {
    setState(() => _busy = true);
    try {
      final result = await widget.api.setGhostMode(visible: value);
      if (mounted) {
        setState(() => _radarVisible = result['radar_visible'] as bool);
      }
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_nickname.isEmpty ? '마이' : _nickname)),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          children: [
            // 내 3D 아바타 — 드래그로 앞뒤 양옆 회전, 코디/설정 변경마다 재생성
            AvatarViewer(bundleUrl: _avatarBundleUrl),
            ListTile(
              leading: const Icon(Icons.face_retouching_natural),
              title: const Text('아바타 수정'),
              subtitle: const Text('피부 톤 · 헤어스타일 · 헤어 컬러'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () async {
                final changed = await Navigator.of(context).push<bool>(
                    MaterialPageRoute(
                        builder: (_) => AvatarEditorScreen(api: widget.api)));
                if (changed == true) _load(); // 재생성 완료 전이면 당겨서 새로고침으로 재확인
              },
            ),
            const Divider(),
            SwitchListTile(
              value: _radarVisible,
              onChanged: _busy ? null : _toggleVisibility,
              title: const Text('레이더에 나를 노출'),
              subtitle: Text(_radarVisible
                  ? '같은 동네 유저의 갤러리·레이더에 보입니다'
                  : '고스트 모드 — 아무에게도 보이지 않습니다 (기본값)'),
              secondary: Icon(
                  _radarVisible ? Icons.visibility : Icons.visibility_off),
            ),
            const Divider(),
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Text('찜한 아이템',
                  style: TextStyle(fontWeight: FontWeight.bold)),
            ),
            if (_likes.isEmpty)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('아직 찜한 아이템이 없습니다'),
              )
            else
              ..._likes.map((item) => ListTile(
                    leading: const Icon(Icons.favorite, color: Colors.pink),
                    title: Text([item['brand_info'], item['category']]
                        .whereType<String>()
                        .join(' ')),
                    trailing: IconButton(
                      icon: const Icon(Icons.close),
                      onPressed: () async {
                        await widget.api.unlikeItem(item['id'] as int);
                        _load();
                      },
                    ),
                  )),
          ],
        ),
      ),
    );
  }
}
