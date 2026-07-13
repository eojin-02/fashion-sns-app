import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../core/api_client.dart';

/// 내 옷장 — 아이템 목록 + 사진 업로드→비동기 스캔 (설계서 4.1).
///
/// 업로드 플로우: 갤러리 선택 → Presigned URL 발급 → S3 직접 PUT →
/// POST /scan(202) → 목록에 PENDING으로 표시. 분석 완료는 서버가
/// WebSocket(/topic/scan/{userId})으로 알리며, MVP에선 당겨서 새로고침으로도 갱신.
class WardrobeScreen extends StatefulWidget {
  const WardrobeScreen({super.key, required this.api});

  final ApiClient api;

  @override
  State<WardrobeScreen> createState() => _WardrobeScreenState();
}

class _WardrobeScreenState extends State<WardrobeScreen> {
  final _picker = ImagePicker();
  List<Map<String, dynamic>> _items = [];
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

    setState(() => _uploading = true);
    try {
      // ① Presigned URL 발급 → ② S3 직접 업로드(서버 미경유) → ③ 스캔 큐 적재
      final upload = await widget.api.createUploadUrl();
      await widget.api.uploadImage(
          upload['presigned_url'] as String, await picked.readAsBytes());
      await widget.api.requestScan(upload['image_key'] as String);
      _snack('업로드 완료 — AI 분석 중입니다');
      await _refresh();
    } on ApiException catch (e) {
      _snack(e.statusCode == 429 ? '오늘 스캔 한도를 다 썼습니다' : e.message);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('내 옷장')),
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
                  : ListView.builder(
                      itemCount: _items.length,
                      itemBuilder: (context, i) => _ItemTile(item: _items[i]),
                    ),
            ),
    );
  }
}

class _ItemTile extends StatelessWidget {
  const _ItemTile({required this.item});

  final Map<String, dynamic> item;

  @override
  Widget build(BuildContext context) {
    final status = item['scan_status'] as String? ?? 'PENDING';
    final (chipColor, chipLabel) = switch (status) {
      'DONE' => (Colors.green, '분석 완료'),
      'FAILED' => (Colors.red, '분석 실패'),
      _ => (Colors.orange, '분석 중'),
    };
    final title = [item['brand_info'], item['category']]
        .whereType<String>()
        .join(' ');
    return ListTile(
      leading: const Icon(Icons.checkroom, size: 36),
      title: Text(title.isEmpty ? '아이템 #${item['id']}' : title),
      subtitle: Text('${item['meta_data'] ?? ''}',
          maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: Chip(
        label: Text(chipLabel, style: const TextStyle(fontSize: 12)),
        backgroundColor: chipColor.withValues(alpha: 0.15),
        side: BorderSide(color: chipColor),
      ),
    );
  }
}
