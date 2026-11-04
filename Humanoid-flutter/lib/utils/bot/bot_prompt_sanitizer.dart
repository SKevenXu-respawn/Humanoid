class BotPromptSanitizer {
  const BotPromptSanitizer._();

  static String clean(String? value) {
    if (value == null || value.isEmpty) {
      return value ?? '';
    }
    final text = value.replaceAll('\\', '/');
    final buffer = StringBuffer();
    for (var i = 0; i < text.length; i++) {
      final code = text.codeUnitAt(i);
      if (code >= 0xD800 && code <= 0xDBFF) {
        if (i + 1 < text.length) {
          final next = text.codeUnitAt(i + 1);
          if (next >= 0xDC00 && next <= 0xDFFF) {
            buffer.writeCharCode(code);
            buffer.writeCharCode(next);
            i++;
          }
        }
      } else if (code >= 0xDC00 && code <= 0xDFFF) {
        continue;
      } else if (code == 0x0A ||
          code == 0x0D ||
          code == 0x09 ||
          code >= 0x20) {
        buffer.writeCharCode(code);
      }
    }
    return buffer.toString();
  }

  static String truncate(String? value, int max) {
    if (value == null || value.isEmpty) {
      return value ?? '';
    }
    if (value.length <= max) {
      return value;
    }
    var end = max;
    if (end > 0) {
      final code = value.codeUnitAt(end - 1);
      if (code >= 0xD800 && code <= 0xDBFF) {
        end--;
      }
    }
    return '${value.substring(0, end)}…';
  }
}
