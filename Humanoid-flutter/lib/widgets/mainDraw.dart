import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppConfig.dart';

import '../config/AppColors.dart';

class MainDraw extends StatelessWidget {
  final void Function(String title) callback;
  MainDraw({super.key,required this.callback});

  final List<String> drawList = ['首页', '配置'];
  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: BoxConstraints(
        minWidth: context.screenWidth / 6,
        maxWidth: context.screenWidth / 6,
        minHeight: context.screenHeight,
        maxHeight: context.screenHeight,
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              children: List.generate(drawList.length, (index) {
                final item = drawList[index];
                return _buildDrawItem(item, callback: (title) =>callback.call(title));
              }),
            ),
          ),
          const VerticalDivider(
            width: 1,
          ),
        ],
      ),
    );
  }

  Widget _buildDrawItem(
    String title, {
    required void Function(String name) callback,
  }) {
    return ListTile(
      title: Text(
        title,
        style: TextStyle(fontSize: 16, color: AppColors.black),
        textAlign: .center
      ),
      titleAlignment:.center,
      onTap: ()=>callback.call(title),
    );
  }
}
