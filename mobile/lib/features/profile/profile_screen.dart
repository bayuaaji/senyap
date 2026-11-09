import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/ui/ui.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/session.dart';
import '../onboarding/onboarding_scaffold.dart';
import 'alias_avatar.dart';
import 'alias_editor.dart';
import 'profile_models.dart';
import 'profile_pickers.dart';
import 'profile_repository.dart';

/// Profil (dari tab Pengaturan): avatar besar, ganti alias (maks. 1× per 7 hari), gender, avatar.
class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  bool _saving = false;

  Future<void> _update(Future<Profile> Function(ProfileRepository repo) change) async {
    setState(() => _saving = true);
    try {
      final profile = await change(ref.read(profileRepositoryProvider));
      ref.read(sessionProvider.notifier).profileUpdated(profile);
    } on ApiError catch (e) {
      if (mounted) showError(context, e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _changeAlias(Profile profile) => Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (context) => Scaffold(
          appBar: const SenyapBar(),
          body: SafeArea(
            top: false,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(Space.gutter, Space.md, Space.gutter, Space.xl),
              children: [
                Text('Ganti alias', style: context.text.headlineLarge),
                const SizedBox(height: Space.sm),
                Text('Semua post, balasan, dan Bisik lamamu ikut memakai alias baru. '
                    'Setelah diganti, alias bisa diganti lagi seminggu kemudian.',
                    style: context.text.bodyMedium),
                const SizedBox(height: Space.xl),
                AliasEditor(
                  gender: profile.gender,
                  initial: profile.alias,
                  emoji: profile.avatar?.emoji,
                  onSaved: (p) {
                    ref.read(sessionProvider.notifier).profileUpdated(p);
                    Navigator.of(context).pop();
                  },
                ),
              ],
            ),
          ),
        ),
      ));

  static String _date(DateTime d) {
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
    return '${d.day} ${months[d.month - 1]} ${d.year}';
  }

  @override
  Widget build(BuildContext context) {
    final profile = ref.watch(sessionProvider).profile;
    return Scaffold(
      appBar: SenyapBar(
        title: 'Profil',
        actions: [
          SizedBox(width: 44, child: _saving ? const Loader(size: 18) : null),
        ],
      ),
      body: profile == null
          ? const Center(child: Text('Profil belum termuat.'))
          : ListView(padding: const EdgeInsets.only(top: Space.md, bottom: Space.x4), children: [
              // Identitas: avatar besar + alias serif, tanpa kotak.
              Padding(
                padding: const EdgeInsets.fromLTRB(Space.gutter, Space.xs, Space.gutter, 0),
                child: Row(children: [
                  AnimatedSwitcher(
                    duration: Motion.base,
                    child: AliasAvatar(
                      key: ValueKey(profile.avatar?.id),
                      alias: profile.alias,
                      emoji: profile.avatar?.emoji,
                      radius: 36,
                    ),
                  ),
                  const SizedBox(width: Space.md),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      AliasName(
                        key: const Key('profile-alias'),
                        text: profile.alias.display,
                        gender: profile.gender,
                        style: context.text.headlineSmall,
                      ),
                      const SizedBox(height: Space.xxs),
                      Text('Hanya alias ini yang dilihat orang lain.', style: context.text.bodyMedium),
                    ]),
                  ),
                ]),
              ),
              const SectionLabel('Alias'),
              GroupCard(children: [
                GroupTile(
                  key: const Key('profile-change-alias'),
                  icon: Icons.badge_outlined,
                  title: 'Ganti alias',
                  subtitle: profile.aliasChangeableAt == null
                      ? 'Bisa diganti seminggu sekali.'
                      : 'Bisa diganti lagi mulai ${_date(profile.aliasChangeableAt!.toLocal())}.',
                  onTap: profile.aliasChangeableAt == null ? () => _changeAlias(profile) : null,
                ),
              ]),
              const SectionLabel('Gender'),
              Padding(
                padding: Space.screen,
                child: GenderPicker(
                  compact: true,
                  value: profile.gender,
                  onChanged: _saving ? null : (g) => _update((r) => r.setGender(g)),
                ),
              ),
              const SectionLabel('Avatar'),
              Padding(
                padding: Space.screen,
                child: AvatarPicker(
                  gender: profile.gender,
                  alias: profile.alias,
                  selectedId: profile.avatar?.id,
                  onChanged: _saving ? null : (a) => _update((r) => r.setAvatar(a?.id)),
                ),
              ),
            ]),
    );
  }
}
