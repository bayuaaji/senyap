import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:senyap/features/messages/chat_screen.dart';
import 'package:senyap/features/messages/image_viewer.dart';

/// PNG 1×1 transparan.
final _png = Uint8List.fromList(const [
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00,
  0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49,
  0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00,
  0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
]);

class _Host extends StatefulWidget {
  const _Host();

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> {
  bool blurred = true;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: Center(
          child: ChatImage(url: 'http://localhost/dm.jpg', blurred: blurred, onReveal: () => setState(() => blurred = false)),
        ),
      );
}

double _scale(WidgetTester tester) => tester
    .widget<InteractiveViewer>(find.byKey(const Key('image-viewer-zoom')))
    .transformationController!
    .value
    .getMaxScaleOnAxis();

void main() {
  testWidgets('foto DM: blur → Lihat → ketuk buka layar penuh, ketuk dua kali zoom, tombol tutup', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: _Host()));
    expect(find.byKey(const Key('chat-image')), findsNothing);

    await tester.tap(find.byKey(const Key('chat-image-reveal')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('chat-image')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('image-viewer')), findsOneWidget);
    expect(find.byType(Hero), findsWidgets);

    final zoom = find.byKey(const Key('image-viewer-zoom'));
    await tester.tap(zoom);
    await tester.pump(const Duration(milliseconds: 60));
    await tester.tap(zoom);
    await tester.pumpAndSettle();
    expect(_scale(tester), greaterThan(2));

    await tester.tap(zoom);
    await tester.pump(const Duration(milliseconds: 60));
    await tester.tap(zoom);
    await tester.pumpAndSettle();
    expect(_scale(tester), closeTo(1, 0.01));

    await tester.tap(find.byKey(const Key('image-viewer-close')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('image-viewer')), findsNothing);
  });

  testWidgets('geser ke bawah menutup layar penuh', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: _Host()));
    await tester.tap(find.byKey(const Key('chat-image-reveal')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('chat-image')));
    await tester.pumpAndSettle();

    await tester.drag(find.byKey(const Key('image-viewer-zoom')), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('image-viewer')), findsNothing);
  });

  testWidgets('pratinjau sebelum kirim: Kirim → true, tutup → false', (tester) async {
    bool? result;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async => result = await ImageViewer.open(context, image: MemoryImage(_png), sendLabel: 'Kirim'),
          child: const Text('buka'),
        ),
      ),
    ));

    await tester.tap(find.text('buka'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('image-preview-send')));
    await tester.pumpAndSettle();
    expect(result, isTrue);

    await tester.tap(find.text('buka'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('image-viewer-close')));
    await tester.pumpAndSettle();
    expect(result, isFalse);
  });
}
