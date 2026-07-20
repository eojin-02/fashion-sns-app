import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../core/api_client.dart';

/// 내 옷장 — 아이템 목록 + 사진 업로드→비동기 스캔 (설계서 4.1).
///
/// 업로드 플로우: 갤러리 선택 → Presigned URL 발급 → S3 직접 PUT →
/// POST /scan(202) → 목록에 PENDING으로 표시. 분석 완료는 서버가
/// WebSocket(/topic/scan/{userId})으로 알리며, MVP에선 당겨서 새로고침으로도 갱신.
///
/// 코디 피팅(설계서 4.2): 분석 완료(DONE) 아이템을 탭해 여러 개 고른 뒤
/// "아바타에 입히기" → PUT /codi → 워커가 그 조합으로 3D 아바타를 재생성한다.
class WardrobeScreen extends StatefulWidget {
  const WardrobeScreen({super.key, required this.api});

  final ApiClient api;

  @override
  State<WardrobeScreen> createState() => _WardrobeScreenState();
}

class _WardrobeScreenState extends State<WardrobeScreen> {
  final _picker = ImagePicker();
  List<Map<String, dynamic>> _items = [];
  final Set<int> _selected = {}; // 코디 피팅용 선택 (DONE 아이템만)
  bool _loading = true;
  bool _uploading = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    try {
      final items = await widget.api.getMyItems();
      if (mounted) setState(() { _items = items; _loading = false; });
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _loading = false);
        _snack('목록을 불러오지 못했습니다: ${e.message}');
      }
    }
  }

  Future<void> _pickAndScan() async {
    final picked = await _picker.pickImage(
        source: ImageSource.gallery, imageQuality: 85);
    if (picked == null) return;

    // 상품 URL(선택) — 넣으면 워커가 상품컷·상품명으로 태깅을 강화한다
    final productUrl = await _askProductUrl();
    if (!mounted) return;

    setState(() => _uploading = true);
    try {
      // ① Presigned URL 발급 → ② S3 직접 업로드(서버 미경유) → ③ 스캔 큐 적재
      final upload = await widget.api.createUploadUrl();
      await widget.api.uploadImage(
          upload['presigned_url'] as String, await picked.readAsBytes());
      await widget.api
          .requestScan(upload['image_key'] as String, productUrl: productUrl);
      _snack('업로드 완료 — AI 분석 중입니다');
      await _refresh();
    } on ApiException catch (e) {
      _snack(e.statusCode == 429 ? '오늘 스캔 한도를 다 썼습니다' : e.message);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  /// 상품 페이지 URL 입력 (선택) — 건너뛰거나 닫으면 null
  Future<String?> _askProductUrl() {
    final controller = TextEditingController();
    String? errorText;
    return showDialog<String>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('상품 링크 (선택)'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('쇼핑몰 상품 페이지 주소를 넣으면 브랜드·색상 인식이 정확해집니다.'),
              const SizedBox(height: 12),
              TextField(
                controller: controller,
                keyboardType: TextInputType.url,
                decoration: InputDecoration(
                  hintText: 'https://...',
                  errorText: errorText,
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('건너뛰기'),
            ),
            FilledButton(
              onPressed: () {
                final url = controller.text.trim();
                if (url.isEmpty) {
                  Navigator.pop(context);
                  return;
                }
                if (!url.startsWith('http://') && !url.startsWith('https://')) {
                  setDialogState(
                      () => errorText = 'http:// 또는 https:// 주소만 가능합니다');
                  return;
                }
                Navigator.pop(context, url);
              },
              child: const Text('확인'),
            ),
          ],
        ),
      ),
    );
  }

  /// 선택한 조합을 아바타에 입힌다 — 서버가 재생성 잡을 큐에 넣고 202처럼 동작
  Future<void> _applyCodi() async {
    try {
      await widget.api.setCodi(_selected.toList());
      setState(() => _selected.clear());
      _snack('아바타에 입혔습니다 — 잠시 후 마이페이지에서 확인하세요');
    } on ApiException catch (e) {
      _snack(e.message);
    }
  }

  void _toggleSelect(int itemId) {
    setState(() {
      if (!_selected.remove(itemId)) {
        _selected.add(itemId);
      }
    });
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_selected.isEmpty ? '내 옷장' : '${_selected.length}개 선택'),
        actions: [
          if (_selected.isNotEmpty) ...[
            TextButton.icon(
              onPressed: _applyCodi,
              icon: const Icon(Icons.accessibility_new),
              label: const Text('아바타에 입히기'),
            ),
            IconButton(
              icon: const Icon(Icons.close),
              tooltip: '선택 해제',
              onPressed: () => setState(() => _selected.clear()),
            ),
          ],
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _uploading ? null : _pickAndScan,
        icon: _uploading
            ? const SizedBox(
                width: 18, height: 18,
                child: CircularProgressIndicator(strokeWidth: 2))
            : const Icon(Icons.add_a_photo),
        label: Text(_uploading ? '업로드 중…' : '옷 등록'),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: _items.isEmpty
                  ? ListView(children: const [
                      SizedBox(height: 200),
                      Center(child: Text('아직 등록된 옷이 없습니다.\n오른쪽 아래 버튼으로 첫 옷을 등록해보세요!',
                          textAlign: TextAlign.center)),
                    ])
                  : ListView(children: _categoryFolders()),
            ),
    );
  }

  static const _categoryOrder = ['아우터', '상의', '하의', '신발', '액세서리'];

  /// 카테고리별 폴더(펼침/접힘) + 폴더 안 2열 그리드
  List<Widget> _categoryFolders() {
    final grouped = <String, List<Map<String, dynamic>>>{};
    for (final item in _items) {
      final category = item['category'] as String? ?? '분석 중';
      grouped.putIfAbsent(category, () => []).add(item);
    }
    final orderedKeys = [
      ..._categoryOrder.where(grouped.containsKey),
      ...grouped.keys.where((k) => !_categoryOrder.contains(k)),
    ];
    return orderedKeys.map((category) {
      final items = grouped[category]!;
      return ExpansionTile(
        initiallyExpanded: true,
        leading: const Icon(Icons.folder_outlined),
        title: Text('$category (${items.length})',
            style: const TextStyle(fontWeight: FontWeight.bold)),
        children: [
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2, // 한 줄에 2개
              crossAxisSpacing: 10,
              mainAxisSpacing: 10,
              childAspectRatio: 0.78,
            ),
            itemCount: items.length,
            itemBuilder: (context, i) {
              final item = items[i];
              final itemId = item['id'] as int;
              final done = item['scan_status'] == 'DONE';
              return _ItemCard(
                item: item,
                selected: _selected.contains(itemId),
                // 분석 완료 아이템만 코디에 담을 수 있다
                onTap: done ? () => _toggleSelect(itemId) : null,
              );
            },
          ),
        ],
      );
    }).toList();
  }
}

class _ItemCard extends StatelessWidget {
  const _ItemCard({required this.item, required this.selected, this.onTap});

  final Map<String, dynamic> item;
  final bool selected;
  final VoidCallback? onTap; // null이면 선택 불가 (분석 전/실패 아이템)

  @override
  Widget build(BuildContext context) {
    final status = item['scan_status'] as String? ?? 'PENDING';
    final (chipColor, chipLabel) = switch (status) {
      'DONE' => (Colors.green, '완료'),
      'FAILED' => (Colors.red, '실패'),
      _ => (Colors.orange, '분석 중'),
    };
    final photoUrl = item['photo_url'] as String?;
    final meta = item['meta_data'];
    final color = meta is Map ? meta['color'] as String? : null;
    final title = [item['brand_info'], color, item['category']]
        .whereType<String>()
        .join(' · ');
    final primary = Theme.of(context).colorScheme.primary;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected ? primary : Colors.white24,
            width: selected ? 2.5 : 1,
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  // 실사 크롭 사진 — 없으면(구버전 스캔) 아이콘 폴백
                  if (photoUrl != null && photoUrl.isNotEmpty)
                    Image.network(
                      photoUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) =>
                          const Center(child: Icon(Icons.checkroom, size: 48)),
                    )
                  else
                    const Center(child: Icon(Icons.checkroom, size: 48)),
                  Positioned(
                    top: 6,
                    left: 6,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: chipColor.withValues(alpha: 0.85),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(chipLabel,
                          style: const TextStyle(
                              fontSize: 10, color: Colors.white)),
                    ),
                  ),
                  if (selected)
                    Positioned(
                      top: 6,
                      right: 6,
                      child: Icon(Icons.check_circle, color: primary),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Text(
                title.isEmpty ? '아이템 #${item['id']}' : title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
