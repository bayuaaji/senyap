import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

class PickedImage {
  const PickedImage(this.bytes, this.mimeType);

  final Uint8List bytes;
  final String mimeType;
}

/// Memilih foto dari galeri HP untuk latar sendiri. Server hanya menerima JPEG/PNG; dengan `maxWidth`,
/// image_picker meng-encode ulang (HEIC → JPEG) dan memperkecil sebelum upload.
abstract interface class BackgroundPicker {
  Future<PickedImage?> pick();
}

final backgroundPickerProvider = Provider<BackgroundPicker>((ref) => ImagePickerBackgroundPicker());

class ImagePickerBackgroundPicker implements BackgroundPicker {
  final _picker = ImagePicker();

  @override
  Future<PickedImage?> pick() async {
    final file = await _picker.pickImage(
      source: ImageSource.gallery,
      maxWidth: 2160,
      maxHeight: 2160,
      imageQuality: 90,
      requestFullMetadata: false,
    );
    if (file == null) return null;
    final bytes = await file.readAsBytes();
    final isPng = bytes.length > 4 && bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
    return PickedImage(bytes, isPng ? 'image/png' : 'image/jpeg');
  }
}
