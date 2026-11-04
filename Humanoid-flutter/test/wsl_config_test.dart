import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/wsl/wsl_utils.dart';

void main() {
  test('生成或更新 mirrored 网络配置', () {
    final empty = WslUtils.applyMirroredConfig('');
    expect(empty, contains('[wsl2]'));
    expect(empty, contains('networkingMode=mirrored'));

    final existing = WslUtils.applyMirroredConfig(
      '[wsl2]\n'
      'memory=4GB\n'
      'networkingMode=nat\n'
      '[experimental]\n'
      'foo=bar\n',
    );
    expect(existing, contains('memory=4GB'));
    expect(existing, contains('networkingMode=mirrored'));
    expect(existing, isNot(contains('networkingMode=nat')));
    expect(existing, contains('[experimental]'));
    expect(existing, contains('foo=bar'));

    final withoutMode = WslUtils.applyMirroredConfig('[wsl2]\nmemory=4GB\n');
    expect(withoutMode, contains('networkingMode=mirrored'));
    expect(withoutMode, contains('memory=4GB'));
  });
}
