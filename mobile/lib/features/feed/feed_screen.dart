import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../posts/location_service.dart';
import '../posts/post_feed_list.dart';
import '../posts/post_models.dart';

/// Tab feed (functional spec FS-03): Terbaru, Populer, Sekitar, Kategori. Semua pull dari server.
class FeedScreen extends StatelessWidget {
  const FeedScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Senyap'),
          bottom: const TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [Tab(text: 'Terbaru'), Tab(text: 'Populer'), Tab(text: 'Sekitar'), Tab(text: 'Kategori')],
          ),
        ),
        body: TabBarView(children: [
          PostFeedList(
            load: (repo, cursor) => repo.feed(FeedKind.latest, cursor: cursor),
            emptyMessage: 'Belum ada cerita di sini.',
          ),
          PostFeedList(
            load: (repo, cursor) => repo.feed(FeedKind.popular, cursor: cursor),
            emptyMessage: 'Belum ada yang ramai hari ini.',
          ),
          const _NearbyTab(),
          const _CategoryTab(),
        ]),
      ),
    );
  }
}

/// Sekitar: lokasi hanya diminta saat user mengetuk tombol; yang dikirim ke server hanya geohash.
class _NearbyTab extends ConsumerStatefulWidget {
  const _NearbyTab();

  @override
  ConsumerState<_NearbyTab> createState() => _NearbyTabState();
}

class _NearbyTabState extends ConsumerState<_NearbyTab> with AutomaticKeepAliveClientMixin {
  String? _cell;
  bool _asking = false;
  bool _denied = false;

  @override
  bool get wantKeepAlive => true;

  Future<void> _enable() async {
    setState(() => _asking = true);
    final cell = await ref.read(locationServiceProvider).currentCell();
    if (!mounted) return;
    setState(() {
      _asking = false;
      _cell = cell;
      _denied = cell == null;
    });
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final cell = _cell;
    if (cell == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.near_me_outlined, size: 48, color: Colors.grey),
            const SizedBox(height: 12),
            Text(
              _denied
                  ? 'Lokasi tidak tersedia. Izinkan akses lokasi di pengaturan HP untuk melihat cerita di sekitarmu.'
                  : 'Lihat cerita dari sekitar ±5 km. Lokasi tepatmu tidak pernah dikirim.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _asking ? null : _enable,
              child: Text(_asking ? 'Mencari lokasi…' : 'Aktifkan lokasi'),
            ),
          ]),
        ),
      );
    }
    return PostFeedList(
      key: ValueKey(cell),
      load: (repo, cursor) => repo.feed(FeedKind.nearby, cursor: cursor, cell: cell),
      emptyMessage: 'Belum ada cerita di sekitarmu.',
    );
  }
}

class _CategoryTab extends StatefulWidget {
  const _CategoryTab();

  @override
  State<_CategoryTab> createState() => _CategoryTabState();
}

class _CategoryTabState extends State<_CategoryTab> with AutomaticKeepAliveClientMixin {
  String _category = postCategories.keys.first;

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Column(children: [
      SizedBox(
        height: 52,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          children: [
            for (final e in postCategories.entries)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  label: Text(e.value),
                  selected: e.key == _category,
                  onSelected: (_) => setState(() => _category = e.key),
                ),
              ),
          ],
        ),
      ),
      Expanded(
        child: PostFeedList(
          key: ValueKey(_category),
          load: (repo, cursor) => repo.feed(FeedKind.category, cursor: cursor, category: _category),
          emptyMessage: 'Belum ada cerita di kategori ini.',
        ),
      ),
    ]);
  }
}
