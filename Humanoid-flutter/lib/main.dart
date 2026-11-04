import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppColors.dart';
import 'package:humanoid_flutter/config/AppConfig.dart';
import 'package:humanoid_flutter/models/mainDrawModels.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:humanoid_flutter/utils/bot/bot_service.dart';
import 'package:humanoid_flutter/utils/terminal/terminal_util.dart';
import 'package:humanoid_flutter/utils/wsl/wsl_utils.dart';
import 'package:humanoid_flutter/widgets/mainDraw.dart';
import 'package:humanoid_flutter/widgets/qr_login_dialog.dart';
import 'package:url_launcher/url_launcher.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await DatabaseUtil.instance.init();
  await WslUtils.instance.init();
  unawaited(TerminalUtil.instance.start());
  await BotService.instance.start();
  runApp(const HumanoidApp());
}

class HumanoidApp extends StatefulWidget {
  const HumanoidApp({super.key});

  @override
  State<HumanoidApp> createState() => _HumanoidAppState();
}

class _HumanoidAppState extends State<HumanoidApp> {

  MainDrawModels _currentModel=AppConfig.mainDrawList.first;
  late final AppLifecycleListener _lifecycleListener;
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  bool _qrDialogOpen = false;
  bool _openingWebUi = false;

  @override
  void initState() {
    super.initState();
    _lifecycleListener = AppLifecycleListener(
      onExitRequested: () async {
        try {
          await BotService.instance.stop();
        } catch (_) {
          // ignore bot cleanup errors on exit
        }
        try {
          await TerminalUtil.instance.stop();
        } catch (_) {
          // ignore cleanup errors on exit
        }
        return AppExitResponse.exit;
      },
    );
    TerminalUtil.instance.qrCodeNotifier.addListener(_handleQrCode);
    TerminalUtil.instance.webUiUrlNotifier.addListener(_handleWebUi);
  }

  @override
  void dispose() {
    TerminalUtil.instance.qrCodeNotifier.removeListener(_handleQrCode);
    TerminalUtil.instance.webUiUrlNotifier.removeListener(_handleWebUi);
    _lifecycleListener.dispose();
    super.dispose();
  }

  void _handleQrCode() {
    final info = TerminalUtil.instance.qrCodeNotifier.value;
    if (info == null || _qrDialogOpen) {
      return;
    }
    final context = _navigatorKey.currentContext;
    if (context == null) {
      return;
    }
    _qrDialogOpen = true;
    showDialog<void>(
      context: context,
      builder: (context) => const QrLoginDialog(),
    ).whenComplete(() {
      _qrDialogOpen = false;
    });
  }

  void _handleWebUi() {
    final url = TerminalUtil.instance.webUiUrlNotifier.value;
    if (url == null || _openingWebUi) {
      return;
    }
    _openingWebUi = true;
    unawaited(_openWebUi(url));
  }

  Future<void> _openWebUi(String url) async {
    try {
      final ok = await launchUrl(
        Uri.parse(url),
        mode: LaunchMode.externalApplication,
      );
      if (ok) {
        _showSnack('WebUI 已启动，正在打开浏览器');
      } else {
        _showSnack('无法打开 WebUI: $url');
      }
    } catch (e) {
      _showSnack('无法打开 WebUI: $e');
    } finally {
      _openingWebUi = false;
    }
  }

  void _showSnack(String message) {
    final context = _navigatorKey.currentContext;
    if (context == null) {
      return;
    }
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      debugShowCheckedModeBanner: false,
      title: 'Humanoid',
      theme: ThemeData(
        colorScheme: .fromSeed(seedColor: AppColors.AppThemeColor),
      ),
      home: Scaffold(
        body: Stack(
          children: [
            Positioned.fill(
              // 关键 1：必须加 ClipRect，防止图片移动时超出屏幕边缘
              child: ClipRect(
                child: Stack(
                  children: [
                    AnimatedPositioned(
                      duration: const Duration(milliseconds: 600),
                      curve: Curves.easeInOutCubic,
                      // 根据 _currentOffset 计算 top 值
                      // _currentOffset 为 0 时居中，-0.5 时上移，0.5 时下移
                      // 这里假设屏幕高度的一半乘以偏移量作为移动距离
                      top: _buildBGOffset(_currentModel,context),
                      left: 0,
                      right: 0,
                      // 关键 2：图片高度必须比屏幕大（比如 1.5 倍），这样向上向下才有空间移动
                      height: context.screenHeight * 1.5,
                      child: Image.asset(
                        'assets/images/backgrounds/app_bg.jpg',
                        fit: BoxFit.cover,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Positioned.fill(
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 10.0, sigmaY: 10.0),
                child: Container(
                  color: Colors.black.withValues(alpha: 0.3),
                ),
              ),
            ),
            Row(
              children: [
                MainDraw(
                  callback: (item) {
                    setState(() {
                      _currentModel = item;
                    });
                  },
                ),
                Expanded(
                    child: _currentModel.widget
                ),
              ],
            ),
          ],
        )
      ),
    );
  }

  double _buildBGOffset(MainDrawModels item, BuildContext context) {
    int index = AppConfig.mainDrawList.indexWhere((a) => a == item);
    int length = AppConfig.mainDrawList.length;

    if (index == -1 || length <= 1) return 0;

    // 多出的空间（1.5 倍图高 - 1 倍屏幕 = 0.5 倍屏幕高度）
    double extraSpace = context.screenHeight * 0.5;

    // 每一份的移动距离
    double step = extraSpace / (length - 1);

    // 核心修改：加负号，让 index 变大时，top 变小（向上移动）
    // 如果你希望第一个菜单在顶部中心，可以保留后面的 -(extraSpace / 2)
    // 如果你希望第一个菜单完全在最顶部，去掉后面的居中偏移即可
    double topOffset = -(index * step);

    return topOffset;
  }
}
