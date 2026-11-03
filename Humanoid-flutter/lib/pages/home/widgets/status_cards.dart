import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:humanoid_flutter/config/AppColors.dart';
import 'package:humanoid_flutter/config/AppEnum.dart';

class StatusCards extends StatelessWidget {
  final String title;
  final AppStatus status;
  const StatusCards({super.key, required this.title, required this.status});

  @override
  Widget build(BuildContext context) {
    final accent = status.color;

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
            offset: const Offset(0, 12),
          ),
          BoxShadow(
            color: accent.withValues(alpha: 0.16),
            blurRadius: 30,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Row(
        children: [
          AnimatedContainer(
            duration: const Duration(milliseconds: 300),
            width: 54,
            height: 54,
            decoration: BoxDecoration(
              color: accent.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: accent.withValues(alpha: 0.20)),
            ),
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 250),
              transitionBuilder: (child, animation) => ScaleTransition(
                scale: animation,
                child: FadeTransition(opacity: animation, child: child),
              ),
              child: Icon(
                status.icon,
                key: ValueKey(status),
                color: accent,
                size: 28,
              ),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: Color(0xDD000000),
                        ),
                      ),
                    ),
                    _StatusPill(status: status),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  status.hint,
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.45,
                    color: AppColors.black.withValues(alpha: 0.82),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    )
        .animate()
        .fadeIn(duration: 280.ms)
        .slideY(begin: 0.18, curve: Curves.easeOutCubic);
  }
}

class _StatusPill extends StatelessWidget {
  final AppStatus status;

  const _StatusPill({required this.status});

  @override
  Widget build(BuildContext context) {
    final accent = status.color;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: accent.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: accent.withValues(alpha: 0.20)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _StatusDot(status: status),
          const SizedBox(width: 6),
          Text(
            status.title,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: accent,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusDot extends StatelessWidget {
  final AppStatus status;

  const _StatusDot({required this.status});

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: 7,
      height: 7,
      decoration: BoxDecoration(color: status.color, shape: BoxShape.circle),
    );

    if (!status.shouldPulse) {
      return dot;
    }

    return SizedBox(
      width: 14,
      height: 14,
      child: Stack(
        alignment: Alignment.center,
        children: [
          dot
              .animate(onPlay: (controller) => controller.repeat())
              .scale(
                end: const Offset(2.2, 2.2),
                duration: 1200.ms,
                curve: Curves.easeOut,
              )
              .fadeOut(duration: 1200.ms),
          dot,
        ],
      ),
    );
  }
}
