import 'package:flutter/foundation.dart';

class SecretHighlightController {
  SecretHighlightController._();

  static final ValueNotifier<Set<String>> keysNotifier =
      ValueNotifier(const <String>{});

  static void highlight(Iterable<String> keys) {
    keysNotifier.value = keys.toSet();
  }

  static void clear() {
    keysNotifier.value = const <String>{};
  }

  static void clearKey(String key) {
    if (!keysNotifier.value.contains(key)) {
      return;
    }
    final next = Set<String>.from(keysNotifier.value)..remove(key);
    keysNotifier.value = next;
  }
}
