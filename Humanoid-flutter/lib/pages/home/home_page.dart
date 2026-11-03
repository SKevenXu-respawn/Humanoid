import 'dart:async';

import 'package:device_inspector/device_inspector.dart';
import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppEnum.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_device_info_card.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_item_title.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_llonebot_section.dart';
import 'package:humanoid_flutter/pages/home/widgets/status_cards.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:humanoid_flutter/utils/wsl/wsl_utils.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}


class _HomePageState extends State<HomePage> {
  bool _isRefreshingWsl = false;
  bool _isInstallingWsl = false;
  bool _isRefreshingDb = false;

  final ValueNotifier<double> cpuNotifier =
  ValueNotifier(0);

  final ValueNotifier<double> memeryNotifier =
  ValueNotifier(0);

  StreamSubscription? _subscription;

  @override
  void initState() {
    // TODO: implement initState
    super.initState();
    _subscription=DeviceInspector.watchMetrics(
      interval: const Duration(seconds: 1), // 每秒推送一次
    ).listen((metrics) {
      debugPrint('cpu=${metrics.cpuUsagePercent}\tmemery=${metrics.memoryUsagePercent}');
      cpuNotifier.value=(metrics.cpuUsagePercent??0)/100;
      memeryNotifier.value=(metrics.memoryUsagePercent??0)/100;
    }, onError: (Object error) {
      debugPrint('设备指标监听失败: $error');
    });
  }

  @override
  void dispose() {
    super.dispose();
    _subscription?.cancel();
  }

  Future<void> _installUbuntu() async {
    if (_isInstallingWsl) {
      return;
    }
    setState(() => _isInstallingWsl = true);

    final result = await WslUtils.instance.requestInstallUbuntuWithAdmin();
    if (!mounted) {
      return;
    }
    setState(() => _isInstallingWsl = false);

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          result == WslStatus.install
              ? '已发起 Ubuntu 安装，请在管理员窗口完成安装后点击刷新'
              : '安装请求失败，请稍后重试',
        ),
      ),
    );
  }


  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body:SingleChildScrollView(
        physics: const BouncingScrollPhysics(),
        child: Container(
          clipBehavior: Clip.hardEdge,
          decoration: const BoxDecoration(color: Colors.transparent),
          padding: const EdgeInsets.all(16),
          child: Column(
            spacing: 16,
            children: [
              ValueListenableBuilder<WslStatus>(
                valueListenable: WslUtils.instance.statusNotifier,
                builder: (context, status, child) {
                  return Column(
                    spacing: 16,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      HomeItemTitle(
                        title: 'WSL环境',
                        onReFresh: _isRefreshingWsl
                            ? null
                            : () async {
                          setState(() => _isRefreshingWsl = true);
                          await WslUtils.instance.refreshStatus();
                          if (mounted) {
                            setState(() => _isRefreshingWsl = false);
                          }
                        },
                      ),
                      StatusCards(title: 'WSL 运行环境', status: status),
                      if (status.needsSetup)
                        FilledButton.icon(
                          onPressed: _isInstallingWsl ? null : _installUbuntu,
                          icon: const Icon(Icons.download_rounded, size: 18),
                          label: Text(
                            status == WslStatus.uninstall
                                ? '安装 WSL + Ubuntu'
                                : '安装 Ubuntu',
                          ),
                          style: FilledButton.styleFrom(
                            backgroundColor: const Color(0xFF3B82F6),
                            foregroundColor: Colors.white,
                            minimumSize: const Size.fromHeight(46),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(14),
                            ),
                          ),
                        ),
                    ],
                  );
                },
              ),
              ValueListenableBuilder<DatabaseStatus>(
                valueListenable: DatabaseUtil.instance.statusNotifier,
                builder: (context, status, child) {
                  return Column(
                    spacing: 16,
                    children: [
                      HomeItemTitle(
                        title: '本地数据库',
                        onReFresh: _isRefreshingDb
                            ? null
                            : () async {
                          setState(() => _isRefreshingDb = true);
                          await DatabaseUtil.instance.refreshStatus();
                          if (mounted) {
                            setState(() => _isRefreshingDb = false);
                          }
                        },
                      ),
                      StatusCards(title: '数据库状态', status: status),
                    ],
                  );
                },
              ),
              HomeItemTitle(
                title: '机器信息',
              ),
              Row(
                spacing: 16,
                children: [
                  Expanded(child:  ValueListenableBuilder<double>(
                    valueListenable: cpuNotifier,
                    builder: (context, cpu, _) {
                      return HomeDeviceInfoCard(title: 'CPU', progress: cpu);
                    },
                  ),),
                  Expanded(child: ValueListenableBuilder<double>(
                    valueListenable: memeryNotifier,
                    builder: (context, memery, _) {
                      return HomeDeviceInfoCard(title: '内存', progress:memery );
                    },
                  ),)
                ],
              ),
              const HomeLLOneBotSection(),
            ],
          ),
        ),
      ),
    );
  }
}
