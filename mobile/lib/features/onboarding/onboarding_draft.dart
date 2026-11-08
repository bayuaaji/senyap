import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Isian onboarding yang belum dikirim. Hanya di memori: nomor HP tidak pernah disimpan di device.
class OnboardingDraft {
  const OnboardingDraft({this.ageConfirmed = false, this.phone});

  final bool ageConfirmed;
  final String? phone;
}

final onboardingDraftProvider =
    NotifierProvider<OnboardingDraftController, OnboardingDraft>(OnboardingDraftController.new);

class OnboardingDraftController extends Notifier<OnboardingDraft> {
  @override
  OnboardingDraft build() => const OnboardingDraft();

  void confirmAge() => state = OnboardingDraft(ageConfirmed: true, phone: state.phone);

  void setPhone(String phone) => state = OnboardingDraft(ageConfirmed: state.ageConfirmed, phone: phone);

  void reset() => state = const OnboardingDraft();
}
