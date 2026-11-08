import 'package:flutter/material.dart';

import 'profile_models.dart';

Color aliasColor(String hex) => Color(int.parse(hex.substring(1), radix: 16) | 0xFF000000);

/// Lingkaran berwarna alias dengan huruf pertama nama hewan.
class AliasAvatar extends StatelessWidget {
  const AliasAvatar({super.key, required this.alias, this.radius = 28});

  final Alias alias;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final bg = aliasColor(alias.color);
    final fg = bg.computeLuminance() > 0.5 ? Colors.black87 : Colors.white;
    return CircleAvatar(
      radius: radius,
      backgroundColor: bg,
      child: Text(alias.name.characters.first, style: TextStyle(color: fg, fontSize: radius * 0.8)),
    );
  }
}
