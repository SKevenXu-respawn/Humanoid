import 'package:flutter/material.dart';
import 'package:humanoid_flutter/config/AppColors.dart';

class HomeInputKeyCard extends StatelessWidget {
  final String title;
  final TextEditingController textEditingController;
  const HomeInputKeyCard({
    super.key,
    required this.textEditingController,
    required this.title,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title,style: TextStyle(fontSize: 16,color: AppColors.black),),
        TextField(
          maxLength: 20,
          controller: textEditingController,
        )
      ],
    );
  }
}
