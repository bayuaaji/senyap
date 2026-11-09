import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../posts/location_prompt.dart';
import '../posts/location_service.dart';
import '../posts/post_feed_list.dart';
import '../posts/post_models.dart';

/// Tab feed (functional spec FS-03): Terbaru, Populer, Sekitar, Kategori. Semua pull dari server.
class FeedScreen extends StatefulWidget {
  const FeedScreen({super.key});

  @override
  State<FeedScreen> createState() => _FeedScreenState();
}

class _FeedScreenState extends State<FeedScreen> with SingleTickerProviderStateMixin {
  late final _tabs = TabController(length: 4, vsync: this)..addListener(_changed);

  void _changed() {
    if (!_tabs.indexIsChanging) setState(() {});
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  /// Hanya tab yang tampil yang boleh punya Hero aktif (post yang sama bisa ada di beberapa tab).
  Widget _tab(int i, Widget child) => HeroMode(enabled: _tabs.index == i, child: child);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const _Wordmark(),
          TextTabs(controller: _tabs, tabs: const [
            TabItem('Terbaru'),
            TabItem('Populer'),
            TabItem('Sekitar'),
            TabItem('Kategori'),
          ]),
          Expanded(
            child: TabBarView(controller: _tabs, children: [
              _tab(
                0,
                PostFeedList(
                  load: (repo, cursor) => repo.feed(FeedKind.latest, cursor: cursor),
                  emptyMessage: 'Belum ada cerita di sini.',
                ),
              ),
              _tab(
                1,
                PostFeedList(
                  load: (repo, cursor) => repo.feed(FeedKind.popular, cursor: cursor),
                  emptyTitle: 'Belum ada yang ramai',
                  emptyMessage: 'Cerita yang banyak ditemani hati dan balasan hari ini akan muncul di sini.',
                ),
              ),
              _tab(2, const _NearbyTab()),
              _tab(3, const _CategoryTab()),
            ]),
          ),
        ]),
      ),
    );
  }
}

class _Wordmark extends StatelessWidget {
  const _Wordmark();

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.sm),
        child: Semantics(header: true, child: Text('Senyap', style: context.text.headlineLarge)),
      );
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
  LocationStatus? _problem;

  @override
  bool get wantKeepAlive => true;

  Future<void> _enable() async {
    final service = ref.read(locationServiceProvider);
    setState(() => _asking = true);
    final result = await service.currentCell();
    if (!mounted) return;
    setState(() {
      _asking = false;
      _cell = result.cell;
      _problem = result.isOk ? null : result.status;
    });
    if (result.isOk) return;
    final choice = await showLocationProblem(context, result.status);
    if (!mounted) return;
    switch (choice) {
      case LocationChoice.openSettings:
        await openSettingsFor(service, result.status);
      case LocationChoice.retry:
        await _enable();
      case LocationChoice.continueWithout:
      case LocationChoice.cancel:
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final cell = _cell;
    if (cell == null) {
      return SingleChildScrollView(
        padding: const EdgeInsets.only(top: Space.md, bottom: Space.x4),
        child: EmptyState(
          icon: Icons.near_me_outlined,
          title: 'Cerita di sekitarmu',
          message: _problem != null
              ? locationProblemMessage(_problem!)
              : 'Lihat cerita dari radius ±5 km. Lokasi tepatmu tidak pernah dikirim, hanya area kasarnya.',
          action: PrimaryButton(
            expand: false,
            label: _asking ? 'Mencari lokasi…' : 'Aktifkan lokasi',
            onPressed: _asking ? null : _enable,
          ),
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
      const SizedBox(height: Space.sm),
      ChoiceChips<String>(
        options: postCategories,
        selected: _category,
        onSelected: (k) => setState(() => _category = k),
      ),
      const SizedBox(height: Space.xs),
      Expanded(
        child: AnimatedSwitcher(
          duration: Motion.base,
          child: PostFeedList(
            key: ValueKey(_category),
            load: (repo, cursor) => repo.feed(FeedKind.category, cursor: cursor, category: _category),
            emptyMessage: 'Belum ada cerita di kategori ini.',
          ),
        ),
      ),
    ]);
  }
}
