import 'package:flutter_test/flutter_test.dart';
import 'package:humanoid_flutter/utils/wsl/wsl_utils.dart';

void main() {
  test('从 wsl -l -q 输出中识别 Ubuntu 发行版', () {
    expect(
      WslUtils.findUbuntuDistro('docker-desktop\r\nUbuntu\r\n'),
      'Ubuntu',
    );
    expect(WslUtils.findUbuntuDistro('ubuntu-22.04'), 'ubuntu-22.04');
    expect(WslUtils.findUbuntuDistro('docker-desktop'), isNull);
    expect(WslUtils.findUbuntuDistro(''), isNull);

    final nullPadded = String.fromCharCodes(
      'docker-desktop\r\nUbuntu'.codeUnits.expand((unit) => [unit, 0]),
    );
    expect(WslUtils.findUbuntuDistro(nullPadded), 'Ubuntu');
  });
}
