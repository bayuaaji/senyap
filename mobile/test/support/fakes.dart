import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/app/app.dart';
import 'package:senyap/core/api/api_client.dart';
import 'package:senyap/core/api/api_error.dart';
import 'package:senyap/core/auth/token_store.dart';
import 'package:senyap/features/onboarding/auth_repository.dart';
import 'package:senyap/features/profile/profile_models.dart';
import 'package:senyap/features/profile/profile_repository.dart';

const testTokens = Tokens(accessToken: 'akses', refreshToken: 'segar');

class FakeAuthRepository implements AuthRepository {
  FakeAuthRepository({this.isNew = true});

  final bool isNew;
  final requestedPhones = <String>[];
  var loggedOut = false;
  var deleted = false;

  @override
  Future<void> requestOtp(String phone) async => requestedPhones.add(phone);

  @override
  Future<VerifyResult> verify({required String phone, required String code}) async {
    if (code != '000000') {
      throw const ApiError(code: 'OTP_INVALID', message: 'Kode salah.', status: 400);
    }
    return VerifyResult(tokens: testTokens, isNew: isNew);
  }

  @override
  Future<void> logout() async => loggedOut = true;

  @override
  Future<void> deleteAccount() async => deleted = true;
}

class FakeProfileRepository implements ProfileRepository {
  FakeProfileRepository({bool onboarded = true}) : _profile = _make('Kelinci Jingga', '4821', onboarded, null);

  Profile _profile;
  var _suggestions = 0;
  final _tokens = <String, Alias>{};

  static Profile _make(String name, String tag, bool onboarded, DateTime? changeableAt) => Profile(
        publicId: '3f9c0000-0000-4000-8000-000000000000',
        alias: Alias(name: name, tag: tag, color: '#F28C38'),
        onboarded: onboarded,
        aliasChangeableAt: changeableAt,
      );

  @override
  Future<Profile> profile() async => _profile;

  @override
  Future<AliasSuggestion> suggest({String? animal, String? color}) async {
    _suggestions++;
    final alias = Alias(
      name: '${animal ?? (_suggestions.isEven ? 'Rubah' : 'Panda')} ${color ?? 'Biru'}',
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
    _profile = _make(alias.name, alias.tag, true, DateTime.utc(2030, 1, 8));
    return _profile;
  }

  @override
  Future<AliasCatalog> catalog() async => const AliasCatalog(
        animals: ['Kelinci', 'Rubah', 'Panda'],
        colors: [AliasColorOption(name: 'Biru', hex: '#1E88E5'), AliasColorOption(name: 'Jingga', hex: '#F28C38')],
      );
}

Future<void> pumpApp(
  WidgetTester tester, {
  required TokenStore tokens,
  AuthRepository? auth,
  ProfileRepository? profile,
}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      tokenStoreProvider.overrideWithValue(tokens),
      authRepositoryProvider.overrideWithValue(auth ?? FakeAuthRepository()),
      profileRepositoryProvider.overrideWithValue(profile ?? FakeProfileRepository()),
    ],
    child: const SenyapApp(),
  ));
  await tester.pumpAndSettle();
}
