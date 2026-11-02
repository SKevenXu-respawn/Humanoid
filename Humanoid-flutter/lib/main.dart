import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:humanoid_flutter/config/AppColors.dart';
import 'package:humanoid_flutter/pages/home/home_page.dart';
import 'package:humanoid_flutter/utils/database_util.dart';
import 'package:humanoid_flutter/widgets/mainDraw.dart';

Future<void> main() async {
  // WidgetsFlutterBinding.ensureInitialized();
  // await DatabaseUtil.instance.init();
  runApp(const HumanoidApp());
}

class HumanoidApp extends StatefulWidget {
  const HumanoidApp({super.key});

  @override
  State<HumanoidApp> createState() => _HumanoidAppState();
}

class _HumanoidAppState extends State<HumanoidApp> {
  String _currentTitle = '首页';
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Flutter Demo',
      theme: ThemeData(
        colorScheme: .fromSeed(seedColor: AppColors.AppThemeColor),
      ),
      home: Scaffold(
        body: Row(
          children: [
            MainDraw(
              callback: (title) {
                setState(() {
                  _currentTitle = title;
                });
              },
            ),
            Expanded(
              child: _buildRightContent()
            ),
          ],
        ),
      ),
    );
  }

  // 根据状态切换右侧页面
  Widget _buildRightContent() {
    // 使用 Dart 3 的 switch 表达式
    return switch (_currentTitle) {
      '首页' => const HomePage(),
      '用户管理' => const Center(
        child: Text('这里是用户管理页面', style: TextStyle(fontSize: 24)),
      ),
      '设置' => const Center(
        child: Text('这里是设置页面', style: TextStyle(fontSize: 24)),
      ),
      _ => const Center(child: Text('页面不存在')), // 兜底
    };
  }
}
