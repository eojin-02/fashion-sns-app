import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/api_client.dart';
import '../core/avatar_viewer.dart';

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

  // 코디 반응 (좋아요 익명 카운트 + 댓글)
  int _codiLikeCount = 0;
  bool _codiLikedByMe = false;
  List<Map<String, dynamic>> _comments = [];
  final _commentCtrl = TextEditingController();
  bool _sendingComment = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _commentCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final profile = await widget.api.getAvatarProfile(widget.saId);
      List<Map<String, dynamic>> comments = [];
      if ((profile['codi_items'] as List? ?? []).isNotEmpty) {
        try {
          comments = await widget.api.getCodiComments(widget.saId);
        } on ApiException {
          // 코디 없음(404) 등 — 댓글 섹션만 비워둔다
        }
      }
      if (mounted) {
        setState(() {
          _profile = profile;
          _codiLikeCount = (profile['codi_like_count'] as num?)?.toInt() ?? 0;
          _codiLikedByMe = profile['codi_liked_by_me'] as bool? ?? false;
          _comments = comments;
        });
      }
    } on ApiException {
      // 세션 만료·고스트 전환·차단 등은 전부 404 — 이유는 구분하지 않는다(프라이버시)
      if (mounted) setState(() => _error = '지금은 볼 수 없는 프로필입니다');
    }
  }

  Future<void> _toggleCodiLike() async {
    try {
      if (_codiLikedByMe) {
        await widget.api.unlikeCodi(widget.saId);
        setState(() {
          _codiLikedByMe = false;
          _codiLikeCount = _codiLikeCount > 0 ? _codiLikeCount - 1 : 0;
        });
      } else {
        await widget.api.likeCodi(widget.saId);
        setState(() {
          _codiLikedByMe = true;
          _codiLikeCount += 1;
        });
      }
    } on ApiException catch (e) {
      _snack(e.message);
    }
  }

  Future<void> _sendComment() async {
    final content = _commentCtrl.text.trim();
    if (content.isEmpty || _sendingComment) return;
    setState(() => _sendingComment = true);
    try {
      final created = await widget.api.postCodiComment(widget.saId, content);
      if (mounted) {
        setState(() {
          _comments.add(created);
          _commentCtrl.clear();
        });
      }
    } on ApiException catch (e) {
      _snack(e.message);
    } finally {
      if (mounted) setState(() => _sendingComment = false);
    }
  }

  Future<void> _deleteComment(int commentId) async {
    try {
      await widget.api.deleteCodiComment(commentId);
      if (mounted) {
        setState(() =>
            _comments.removeWhere((c) => c['comment_id'] == commentId));
      }
    } on ApiException catch (e) {
      _snack(e.message);
    }
  }

  Future<void> _reportComment(int commentId) async {
    try {
      await widget.api.reportCodiComment(commentId);
      _snack('댓글 신고가 접수되었습니다');
    } on ApiException catch (e) {
      _snack(e.message);
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
    // 키보드가 올라오면 입력창이 가려지지 않게 inset만큼 밀어올린다
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(24, 24, 24, bottomInset + 24),
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
    final hasCodi = items.isNotEmpty;
    return ConstrainedBox(
      constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(child: SingleChildScrollView(child: _content(profile, items))),
          if (hasCodi) _commentInput(),
        ],
      ),
    );
  }

  Widget _content(Map<String, dynamic> profile, List<Map<String, dynamic>> items) {
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
        const SizedBox(height: 12),
        // 3D 아바타 — 드래그로 앞뒤 양옆 회전 (설계서 4.2)
        AvatarViewer(bundleUrl: profile['avatar_bundle_url'] as String?),
        const SizedBox(height: 12),
        const Text('오늘의 코디', style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        if (items.isEmpty)
          const Text('아직 등록된 코디가 없습니다')
        else
          ...items.map((item) {
            final itemId = item['item_id'] as int;
            final productUrl = item['product_url'] as String?;
            final title = [item['brand_info'], item['category']]
                .whereType<String>()
                .join(' ');
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.checkroom),
              title: Text(title.isEmpty ? '아이템' : title),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (productUrl != null && productUrl.isNotEmpty)
                    IconButton(
                      icon: const Icon(Icons.open_in_new),
                      tooltip: '사러 가기',
                      onPressed: () => launchUrl(Uri.parse(productUrl),
                          mode: LaunchMode.externalApplication),
                    ),
                  IconButton(
                    icon: Icon(
                      _liked.contains(itemId)
                          ? Icons.favorite
                          : Icons.favorite_border,
                      color: Colors.pink,
                    ),
                    onPressed:
                        _liked.contains(itemId) ? null : () => _like(itemId),
                  ),
                ],
              ),
            );
          }),
        if (items.isNotEmpty) ...[
          const Divider(height: 24),
          // 코디 전체에 대한 반응 — 좋아요는 익명 카운트만 (누가 눌렀는지 비공개)
          Row(
            children: [
              IconButton(
                icon: Icon(
                  _codiLikedByMe ? Icons.favorite : Icons.favorite_border,
                  color: Colors.pink,
                ),
                tooltip: '이 코디 좋아요',
                onPressed: _toggleCodiLike,
              ),
              Text('좋아요 $_codiLikeCount개'),
              const SizedBox(width: 16),
              const Icon(Icons.chat_bubble_outline, size: 16),
              const SizedBox(width: 4),
              Text('${_comments.length}'),
            ],
          ),
          ..._comments.map(_commentTile),
        ],
      ],
    );
  }

  Widget _commentTile(Map<String, dynamic> comment) {
    final commentId = comment['comment_id'] as int;
    final mine = comment['is_mine'] as bool? ?? false;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text.rich(TextSpan(children: [
              TextSpan(
                  text: '${comment['nickname']}  ',
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              TextSpan(text: comment['content'] as String? ?? ''),
            ])),
          ),
          // 본인 댓글은 삭제, 타인 댓글은 신고 (UGC 정책)
          SizedBox(
            width: 32,
            height: 32,
            child: IconButton(
              padding: EdgeInsets.zero,
              iconSize: 16,
              icon: Icon(mine ? Icons.delete_outline : Icons.flag_outlined),
              tooltip: mine ? '삭제' : '신고',
              onPressed: () => mine
                  ? _deleteComment(commentId)
                  : _reportComment(commentId),
            ),
          ),
        ],
      ),
    );
  }

  Widget _commentInput() {
    return Row(
      children: [
        Expanded(
          child: TextField(
            controller: _commentCtrl,
            maxLength: 200,
            decoration: const InputDecoration(
              hintText: '댓글 달기…',
              counterText: '',
              isDense: true,
            ),
            onSubmitted: (_) => _sendComment(),
          ),
        ),
        IconButton(
          icon: _sendingComment
              ? const SizedBox(
                  width: 18, height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.send),
          onPressed: _sendingComment ? null : _sendComment,
        ),
      ],
    );
  }
}
