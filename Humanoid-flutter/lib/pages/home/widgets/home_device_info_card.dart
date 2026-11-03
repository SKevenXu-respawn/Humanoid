import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:humanoid_flutter/config/AppColors.dart';

class HomeDeviceInfoCard extends StatefulWidget {
  final String title;
  final double progress;
  const HomeDeviceInfoCard({
    super.key,
    required this.title,
    required this.progress,
  });

  @override
  State<HomeDeviceInfoCard> createState() => _HomeDeviceInfoCardState();
}

class _HomeDeviceInfoCardState extends State<HomeDeviceInfoCard> {
  double get _value => widget.progress.clamp(0.0, 1.0);

  Color get _accent {
    if (_value < 0.6) return const Color(0xFF22C55E);
    if (_value < 0.85) return const Color(0xFFF59E0B);
    return const Color(0xFFEF4444);
  }

  String get _levelLabel {
    if (_value < 0.6) return '平稳';
    if (_value < 0.85) return '偏高';
    return '过高';
  }

  String get _hint {
    if (_value < 0.6) return '当前负载平稳，运行正常';
    if (_value < 0.85) return '当前负载偏高，注意观察';
    return '当前负载过高，请检查占用';
  }

  @override
  Widget build(BuildContext context) {
    final accent = _accent;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Colors.white.withValues(alpha: 0.84),
            Colors.white.withValues(alpha: 0.60),
          ],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withValues(alpha: 0.70)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 24,
            offset: const Offset(0, 3),
          ),
          BoxShadow(
            color: accent.withValues(alpha: 0.16),
            blurRadius: 30,
            offset: const Offset(0, 1),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisAlignment: .spaceBetween,
            children: [
              Column(
                crossAxisAlignment: .start,
                spacing: 6,
                children: [
                  Text(
                    widget.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: Color(0xDD000000),
                    ),
                  ),
                  Text(
                    _hint,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      height: 1.45,
                      color: AppColors.black.withValues(alpha: 0.82),
                    ),
                  ),
                ],
              ),
              _LevelPill(label: _levelLabel, color: accent),
            ],
          ),

          _buildGauge(accent),
        ],
      )
    )
        .animate()
        .fadeIn(duration: 280.ms)
        .slideY(begin: 0.18, curve: Curves.easeOutCubic);
  }

  Widget _buildGauge(Color accent) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: _value),
      duration: const Duration(milliseconds: 600),
      curve: Curves.easeOutCubic,
      builder: (context, value, child) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              '${(value * 100).round()}%',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: accent,
              ),
            ),
            LinearProgressIndicator(
              borderRadius: .all(.circular(25)),
              value: value,
              backgroundColor: accent.withValues(alpha: 0.12),
              valueColor: AlwaysStoppedAnimation<Color>(accent),
            ),
          ],
        );
      },
    );
  }
}

class _LevelPill extends StatelessWidget {
  final String label;
  final Color color;

  const _LevelPill({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.20)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}
