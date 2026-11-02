import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:humanoid_flutter/pages/home/widgets/home_input_key_card.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final TextEditingController dsKeyController = TextEditingController();
  final TextEditingController llOneBotKeyController = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body:Container(
        clipBehavior: .hardEdge,
        decoration: BoxDecoration(
          color: Colors.transparent
        ),
        padding: .all(16),
        child: Column(
          children: [
            HomeInputKeyCard(
              textEditingController: dsKeyController,
              title: 'DeepSeek Key',
            ).animate().fadeIn(duration: 100.ms).slideX(begin: -1),
            HomeInputKeyCard(
              textEditingController: llOneBotKeyController,
              title: 'LLOneBot Key',
            ).animate(delay: 100.ms).fadeIn(duration: 100.ms).slideX(begin: -1),
          ],
        ),
      )
    );
  }
}
