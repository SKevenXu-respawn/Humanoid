package com.skevenxu.humanoid.ai.monitor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 调度器最近一次运行状态（单 JVM 内存态，供管理台展示）。 */
public final class SchedulerStatus {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final String REMINDERS = "reminders";
    public static final String VIDEO_QUEUE = "videoQueue";
    public static final String TASK_EXECUTOR = "taskExecutor";
    public static final String PROACTIVE = "proactive";
    public static final String MEMORY_SUMMARY = "memorySummary";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(REMINDERS, "提醒派发");
        LABELS.put(VIDEO_QUEUE, "视频队列");
        LABELS.put(TASK_EXECUTOR, "任务执行器");
        LABELS.put(PROACTIVE, "主动行为");
        LABELS.put(MEMORY_SUMMARY, "记忆总结");
    }

    private static final Map<String, Entry> STATUS = new ConcurrentHashMap<>();

    private SchedulerStatus() {
    }

    public static void record(String name, String detail) {
        STATUS.put(name, new Entry(name, LABELS.getOrDefault(name, name), detail, LocalDateTime.now(ZONE)));
    }

    public static List<Entry> snapshot() {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, String> label : LABELS.entrySet()) {
            Entry entry = STATUS.get(label.getKey());
            list.add(new Entry(label.getKey(), label.getValue(),
                    entry == null ? "尚未运行" : entry.detail(),
                    entry == null ? null : entry.lastRunAt()));
        }
        return list;
    }

    public record Entry(String name, String label, String detail, LocalDateTime lastRunAt) {
    }
}
