import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/app/app.dart';
import 'package:senyap/core/api/api_client.dart';
import 'package:senyap/core/api/api_error.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/messages/chat_models.dart';
import 'package:senyap/features/messages/chat_providers.dart';
import 'package:senyap/features/messages/chat_repository.dart';
import 'package:senyap/features/compose/background_picker.dart';
import 'package:senyap/features/onboarding/auth_repository.dart';
import 'package:senyap/features/posts/location_service.dart';
import 'package:senyap/features/posts/posts_repository.dart';
import 'package:senyap/features/safety/safety_repository.dart';

import 'fake_posts.dart';

export 'fake_posts.dart';

import 'package:senyap/features/profile/profile_models.dart';
import 'package:senyap/features/profile/profile_repository.dart';

const testTokens = Tokens(accessToken: 'akses', refreshToken: 'segar');

class FakeAuthRepository implements AuthRepository {
  FakeAuthRepository({this.isNew = true, Map<String, String>? accounts}) : accounts = accounts ?? {};

  final bool isNew;
  final requestedPhones = <String>[];

  /// email → kata sandi.
  final Map<String, String> accounts;
  final forgotRequests = <String>[];
  var loggedOut = false;
  var deleted = false;

  @override
  Future<void> requestOtp(String phone) async => requestedPhones.add(phone);

  @override
  Future<VerifyResult> verify({
    required String phone,
    required String code,
  }) async {
    if (code != '000000') {
      throw const ApiError(
        code: 'OTP_INVALID',
        message: 'Kode salah.',
        status: 400,
      );
    }
    return VerifyResult(tokens: testTokens, isNew: isNew);
  }

  @override
  Future<VerifyResult> registerEmail({required String email, required String password}) async {
    if (accounts.containsKey(email)) {
      throw const ApiError(code: 'EMAIL_TAKEN', message: 'Email sudah dipakai.', status: 409);
    }
    if (password.length < 8) {
      throw const ApiError(code: 'WEAK_PASSWORD', message: 'Lemah.', status: 400);
    }
    accounts[email] = password;
    return const VerifyResult(tokens: testTokens, isNew: true);
  }

  @override
  Future<VerifyResult> loginEmail({required String email, required String password}) async {
    if (accounts[email] != password) {
      throw const ApiError(code: 'INVALID_CREDENTIALS', message: 'Salah.', status: 401);
    }
    return const VerifyResult(tokens: testTokens, isNew: false);
  }

  @override
  Future<void> forgotPassword(String email) async => forgotRequests.add(email);

  @override
  Future<void> resetPassword({required String email, required String code, required String newPassword}) async {
    if (code != '000000') {
      throw const ApiError(code: 'RESET_CODE_INVALID', message: 'Kode reset salah.', status: 400);
    }
    accounts[email] = newPassword;
  }

  @override
  Future<void> logout() async => loggedOut = true;

  @override
  Future<void> deleteAccount() async => deleted = true;
}

class FakeProfileRepository implements ProfileRepository {
  FakeProfileRepository({bool onboarded = true, Gender gender = Gender.unspecified, AvatarOption? avatar})
    : _profile = _make('Kelinci Jingga', '4821', onboarded, null, gender, avatar);

  Profile _profile;
  var _suggestions = 0;
  final _tokens = <String, Alias>{};
  final catalogRequests = <Gender>[];
  final suggestRequests = <String>[];

  Profile get current => _profile;

  static const catalogs = {
    Gender.male: AliasCatalog(
      gender: Gender.male,
      first: ['Harimau', 'Elang', 'Serigala'],
      second: [AliasColorOption(name: 'Biru', hex: '#1E88E5'), AliasColorOption(name: 'Hijau', hex: '#43A047')],
      avatars: [AvatarOption(id: 'm-singa', emoji: '🦁'), AvatarOption(id: 'm-elang', emoji: '🦅')],
    ),
    Gender.female: AliasCatalog(
      gender: Gender.female,
      first: ['Kucing', 'Merpati', 'Kupu-kupu'],
      second: [AliasColorOption(name: 'Merah Muda', hex: '#EC407A'), AliasColorOption(name: 'Ungu', hex: '#8E24AA')],
      avatars: [AvatarOption(id: 'f-kucing', emoji: '🐱'), AvatarOption(id: 'f-kupu', emoji: '🦋')],
    ),
    Gender.unspecified: AliasCatalog(
      first: ['Kelinci', 'Rubah', 'Panda'],
      second: [AliasColorOption(name: 'Biru', hex: '#1E88E5'), AliasColorOption(name: 'Jingga', hex: '#F28C38')],
      avatars: [AvatarOption(id: 'u-panda', emoji: '🐼'), AvatarOption(id: 'u-rubah', emoji: '🦊')],
    ),
  };

  static Profile _make(
    String name,
    String tag,
    bool onboarded,
    DateTime? changeableAt,
    Gender gender,
    AvatarOption? avatar,
  ) => Profile(
    publicId: '3f9c0000-0000-4000-8000-000000000000',
    alias: Alias(name: name, tag: tag, color: '#F28C38'),
    onboarded: onboarded,
    aliasChangeableAt: changeableAt,
    gender: gender,
    avatar: avatar,
  );

  Profile _copy({Alias? alias, bool? onboarded, DateTime? changeableAt, Gender? gender, AvatarOption? avatar,
          bool clearAvatar = false}) =>
      _make(
        (alias ?? _profile.alias).name,
        (alias ?? _profile.alias).tag,
        onboarded ?? _profile.onboarded,
        changeableAt ?? _profile.aliasChangeableAt,
        gender ?? _profile.gender,
        clearAvatar ? null : (avatar ?? _profile.avatar),
      );

  @override
  Future<Profile> profile() async => _profile;

  @override
  Future<AliasSuggestion> suggest({String? first, String? second}) async {
    _suggestions++;
    suggestRequests.add('${first ?? '*'}+${second ?? '*'}');
    final words = catalogs[_profile.gender]!.first;
    final alias = Alias(
      name: '${first ?? words[_suggestions % words.length]} ${second ?? 'Biru'}',
      tag: (1000 + _suggestions).toString(),
      color: '#1E88E5',
    );
    final token = 'saran-$_suggestions';
    _tokens[token] = alias;
    return AliasSuggestion(alias: alias, token: token);
  }

  @override
  Future<Profile> useAlias(String suggestionToken) async {
    final alias = _tokens.remove(suggestionToken)!;
    _profile = _copy(alias: alias, onboarded: true, changeableAt: DateTime.utc(2030, 1, 8));
    return _profile;
  }

  @override
  Future<AliasCatalog> catalog(Gender gender) async {
    catalogRequests.add(gender);
    return catalogs[gender]!;
  }

  @override
  Future<Profile> setGender(Gender gender) async => _profile = _copy(gender: gender);

  @override
  Future<Profile> setAvatar(String? avatarId) async {
    if (avatarId == null) return _profile = _copy(clearAvatar: true);
    final option = catalogs.values.expand((c) => c.avatars).firstWhere((a) => a.id == avatarId);
    return _profile = _copy(avatar: option);
  }
}

/// Inbox kosong.
class EmptyChatRepository implements ChatRepository {
  @override
  Future<ConversationPage> list({required String box, String? cursor}) async =>
      const ConversationPage([], null);

  @override
  Future<ChatBadge> badge() async =>
      const ChatBadge(unreadMessages: 0, requests: 0);

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError();
}

/// Report/block di memori. [blocked] = daftar blokir yang dikembalikan `blocks()`.
class FakeSafetyRepository implements SafetyRepository {
  final reports = <String>[];
  final blockCalls = <String>[];
  final blocked = <BlockedAccount>[];
  final unblocked = <String>[];

  @override
  Future<void> report(SafetyTarget type, String targetId, String reason, {int? seq, String? note}) async =>
      reports.add('${type.wire}:$targetId${seq == null ? '' : '#$seq'}:$reason');

  @override
  Future<void> block(SafetyTarget type, String targetId, {int? seq}) async {
    blockCalls.add('${type.wire}:$targetId');
    blocked.add(BlockedAccount(id: 'b${blocked.length + 1}', name: 'Rubah Biru', tag: '1203', createdAt: DateTime.utc(2026)));
  }

  @override
  Future<List<BlockedAccount>> blocks() async => List.of(blocked);

  @override
  Future<void> unblock(String blockId) async {
    unblocked.add(blockId);
    blocked.removeWhere((b) => b.id == blockId);
  }

  @override
  Future<SupportCard> supportCard() async => const SupportCard(
        title: 'Kamu tidak sendirian',
        message: 'Ada yang siap mendengarkan.',
        verified: false,
        hotlines: [Hotline(name: 'Layanan contoh', contact: '000')],
      );
}

Future<void> pumpApp(
  WidgetTester tester, {
  required TokenStore tokens,
  AuthRepository? auth,
  ProfileRepository? profile,
  PostsRepository? posts,
  LocationService? location,
  BackgroundPicker? picker,
  SafetyRepository? safety,
  ChatRepository? chat,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        tokenStoreProvider.overrideWithValue(tokens),
        authRepositoryProvider.overrideWithValue(auth ?? FakeAuthRepository()),
        profileRepositoryProvider.overrideWithValue(
          profile ?? FakeProfileRepository(),
        ),
        // Bisik: tanpa WebSocket/SQLite nyata di widget test.
        chatRepositoryProvider.overrideWithValue(chat ?? EmptyChatRepository()),
        chatEngineProvider.overrideWithValue(null),
        postsRepositoryProvider.overrideWithValue(
          posts ?? FakePostsRepository(),
        ),
        locationServiceProvider.overrideWithValue(
          location ?? FakeLocationService(),
        ),
        backgroundPickerProvider.overrideWithValue(
          picker ?? FakeBackgroundPicker(),
        ),
        safetyRepositoryProvider.overrideWithValue(
          safety ?? FakeSafetyRepository(),
        ),
      ],
      child: const SenyapApp(),
    ),
  );
  await tester.pumpAndSettle();
}
