import 'package:flutter/material.dart';

import '../core/api_client.dart';

/// 아바타 프로필 바텀시트.
/// session_avatar_id 하나로 프로필 열람·찜·차단·신고를 전부 처리한다 —
/// 클라이언트는 상대의 user_id를 끝까지 모른다 (설계서 2.2).
Future<void> showAvatarProfileSheet(
    BuildContext context, ApiClient api, String saId) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    builder: (_) => _AvatarProfileSheet(api: api, saId: saId),
  );
}

class _AvatarProfileSheet extends StatefulWidget {
  const _AvatarProfileSheet({required this.api, required this.saId});

  final ApiClient api;
  final String saId;

  @override
  State<_AvatarProfileSheet> createState() => _AvatarProfileSheetState();
}

class _AvatarProfileSheetState extends State<_AvatarProfileSheet> {
  Map<String, dynamic>? _profile;
  String? _error;
  final Set<int> _liked = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final profile = await widget.api.getAvatarProfile(widget.saId);
      if (mounted) setState(() => _profile = profile);
    } on ApiException {
      // 세션 만료·고스트 전환·차단 등은 전부 404 — 이유는 구분하지 않는다(프라이버시)
      if (mounted) setState(() => _error = '지금은 볼 수 없는 프로필입니다');
    }
  }

  Future<void> _like(int itemId) async {
    try {
      await widget.api.likeItem(itemId);
      if (mounted) setState(() => _liked.add(itemId));
    } on ApiException catch (e) {
      _snack(e.message);
    }
  }

  Future<void> _block() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('차단할까요?'),
        content: const Text('서로의 레이더·갤러리에서 더 이상 보이지 않습니다.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('취소')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('차단')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await widget.api.blockAvatar(widget.saId);
      if (mounted) Navigator.pop(context);
    } on ApiException catch (e) {
      _snack(e.message);
    }
  }

  Future<void> _report() async {
    try {
      await widget.api.reportAvatar(widget.saId);
      _snack('신고가 접수되었습니다');
    } on ApiException catch (e) {
      _snack(e.message);
    }
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
      child: _error != null
          ? SizedBox(height: 120, child: Center(child: Text(_error!)))
          : _profile == null
              ? const SizedBox(
                  height: 120,
                  child: Center(child: CircularProgressIndicator()))
              : _body(),
    );
  }

  Widget _body() {
    final profile = _profile!;
    final items = (profile['codi_items'] as List? ?? [])
        .cast<Map<String, dynamic>>();
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const CircleAvatar(radius: 24, child: Icon(Icons.person)),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(profile['nickname'] as String? ?? '',
                      style: Theme.of(context).textTheme.titleMedium),
                  Text(profile['today_style_summary'] as String? ?? '',
                      style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            PopupMenuButton<String>(
              onSelected: (v) => v == 'block' ? _block() : _report(),
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'block', child: Text('차단')),
                PopupMenuItem(value: 'report', child: Text('신고')),
              ],
            ),
          ],
        ),
        const SizedBox(height: 16),
        const Text('오늘의 코디', style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        if (items.isEmpty)
          const Text('아직 등록된 코디가 없습니다')
        else
          ...items.map((item) {
            final itemId = item['item_id'] as int;
            final title = [item['brand_info'], item['category']]
                .whereType<String>()
                .join(' ');
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.checkroom),
              title: Text(title.isEmpty ? '아이템' : title),
              trailing: IconButton(
                icon: Icon(
                  _liked.contains(itemId)
                      ? Icons.favorite
                      : Icons.favorite_border,
                  color: Colors.pink,
                ),
                onPressed:
                    _liked.contains(itemId) ? null : () => _like(itemId),
              ),
            );
          }),
      ],
    );
  }
}
