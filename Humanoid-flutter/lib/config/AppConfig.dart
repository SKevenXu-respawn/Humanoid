import 'package:flutter/material.dart';
import 'package:humanoid_flutter/models/mainDrawModels.dart';
import 'package:humanoid_flutter/pages/configs/configs_page.dart';
import 'package:humanoid_flutter/pages/home/home_page.dart';
import 'package:humanoid_flutter/pages/logs/logs_page.dart';

extension ContextScreenSize on BuildContext {
  double get screenWidth => MediaQuery.sizeOf(this).width;
  double get screenHeight => MediaQuery.sizeOf(this).height;
}

class AppConfig {
  static  List<MainDrawModels> mainDrawList = [
    MainDrawModels(name: '首页', widget: HomePage()),
    MainDrawModels(name: '日志', widget: LogsPage()),
    MainDrawModels(name: '配置', widget: ConfigsPage()),
  ];
}
