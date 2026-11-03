import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';

class HomeItemTitle extends StatelessWidget {
  static const _accent = Color(0xFF3B82F6);

  final String title;
  final void Function()? onReFresh;
  const HomeItemTitle({super.key, this.onReFresh, required this.title});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Colors.white.withValues(alpha: 0.84),
            Colors.white.withValues(alpha: 0.60),
          ],
        ),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withValues(alpha: 0.70)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.06),
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
          BoxShadow(
            color: _accent.withValues(alpha: 0.14),
            blurRadius: 24,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Row(
        children: [
          Container(
            width: 4,
            height: 16,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0xFF60A5FA), Color(0xFF2563EB)],
              ),
              borderRadius: BorderRadius.circular(999),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            title,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: Color(0xDD000000),
              letterSpacing: 0.2,
            ),
          ),
          const Spacer(),
          if (onReFresh != null) _RefreshButton(onPressed: onReFresh!),
        ],
      ),
    )
        .animate()
        .fadeIn(duration: 280.ms)
        .slideY(begin: 0.15, curve: Curves.easeOutCubic);
  }
}

class _RefreshButton extends StatelessWidget {
  final VoidCallback onPressed;

  const _RefreshButton({required this.onPressed});

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: '刷新',
      child: Container(
        width: 32,
        height: 32,
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.55),
          shape: BoxShape.circle,
          border: Border.all(color: Colors.white.withValues(alpha: 0.70)),
          boxShadow: [
            BoxShadow(
              color: HomeItemTitle._accent.withValues(alpha: 0.10),
              blurRadius: 10,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: onPressed,
            hoverColor: Colors.white.withValues(alpha: 0.35),
            child: Icon(
              Icons.refresh_rounded,
              size: 18,
              color: Colors.black.withValues(alpha: 0.60),
            ),
          ),
        ),
      ),
    );
  }
}
