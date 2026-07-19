import 'package:flutter/material.dart';

import '../core/api_client.dart';

/// 아바타 베이스 커스터마이징 (피부/헤어) — 가입 직후 1회 + 마이페이지에서 수정.
///
/// 색상 견본은 ai-server/avatar_builder.py 팔레트와 동기 유지.
/// 저장하면 서버가 현재 착장을 유지한 채 아바타를 재생성한다.
class AvatarEditorScreen extends StatefulWidget {
  const AvatarEditorScreen(
      {super.key, required this.api, this.isSignup = false});

  final ApiClient api;
  final bool isSignup; // 가입 직후면 '건너뛰기' 노출 — 퍼널 이탈 방지

  @override
  State<AvatarEditorScreen> createState() => _AvatarEditorScreenState();
}

class _AvatarEditorScreenState extends State<AvatarEditorScreen> {
  static const _skins = {
    'light': ('밝은 톤', Color(0xFFF5CCB0)),
    'tan': ('태닝 톤', Color(0xFFCC9973)),
    'deep': ('딥 톤', Color(0xFF734D38)),
  };
  static const _hairColors = {
    'black': ('블랙', Color(0xFF1A1A1A)),
    'brown': ('브라운', Color(0xFF59381F)),
    'blonde': ('블론드', Color(0xFFD9B873)),
    'red': ('레드', Color(0xFF8C331F)),
    'blue': ('블루', Color(0xFF4059BF)),
    'pink': ('핑크', Color(0xFFE68CA6)),
  };
  static const _hairStyles = {
    'auto': '📷 사진 자동',
    'short': '숏컷',
    'long': '롱헤어',
    'bald': '민머리',
  };

  String _skin = 'light';
  String _hairColor = 'auto'; // 기본: 스캔 사진에서 감지한 헤어를 따라간다
  String _hairStyle = 'auto';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    if (!widget.isSignup) _loadCurrent();
  }

  Future<void> _loadCurrent() async {
    try {
      final me = await widget.api.getMe();
      final config = (me['avatar_config'] as Map?)?.cast<String, dynamic>();
      if (config != null && mounted) {
        setState(() {
          _skin = config['skin'] as String? ?? _skin;
          _hairColor = config['hair_color'] as String? ?? _hairColor;
          _hairStyle = config['hair_style'] as String? ?? _hairStyle;
        });
      }
    } on ApiException {
      // 기본값으로 시작 — 저장 시점에 다시 검증된다
    }
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await widget.api.updateAvatarConfig({
        'skin': _skin,
        'hair_color': _hairColor,
        'hair_style': _hairStyle,
      });
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('저장했습니다 — 아바타에 반영 중입니다')));
      Navigator.pop(context, true);
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _swatchChips(Map<String, (String, Color)> options, String current,
      ValueChanged<String> onSelect) {
    return Wrap(
      spacing: 8,
      children: options.entries.map((e) {
        final (label, color) = e.value;
        return ChoiceChip(
          avatar: CircleAvatar(backgroundColor: color, radius: 10),
          label: Text(label),
          selected: current == e.key,
          onSelected: (_) => onSelect(e.key),
        );
      }).toList(),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.isSignup ? '아바타 만들기' : '아바타 수정'),
        actions: [
          if (widget.isSignup)
            TextButton(
              // 기본 아바타는 가입 시 이미 생성돼 있으므로 건너뛰어도 안전하다
              onPressed: () => Navigator.pop(context, false),
              child: const Text('건너뛰기'),
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('피부 톤', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          _swatchChips(_skins, _skin, (v) => setState(() => _skin = v)),
          const SizedBox(height: 24),
          const Text('헤어스타일', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: _hairStyles.entries
                .map((e) => ChoiceChip(
                      label: Text(e.value),
                      selected: _hairStyle == e.key,
                      onSelected: (_) => setState(() => _hairStyle = e.key),
                    ))
                .toList(),
          ),
          const SizedBox(height: 24),
          const Text('헤어 컬러', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              ChoiceChip(
                label: const Text('📷 사진 자동'),
                selected: _hairColor == 'auto',
                onSelected: (_) => setState(() => _hairColor = 'auto'),
              ),
              ..._hairColors.entries.map((e) {
                final (label, color) = e.value;
                return ChoiceChip(
                  avatar: CircleAvatar(backgroundColor: color, radius: 10),
                  label: Text(label),
                  selected: _hairColor == e.key,
                  onSelected: (_) => setState(() => _hairColor = e.key),
                );
              }),
            ],
          ),
          const SizedBox(height: 40),
          FilledButton(
            onPressed: _busy ? null : _save,
            child: Text(_busy ? '저장 중…' : '저장'),
          ),
        ],
      ),
    );
  }
}
