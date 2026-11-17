package com.chinazhouwy.miniharness.runtime.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 进程内事件总线：全局 seq 自增，同步扇出到所有监听器。
 * SSE 推送、评测审计、持久化都以监听器形式接入；本类不做过滤与缓存策略。
 */
public class EventBus {

    private final AtomicLong seq = new AtomicLong();
    private final List<Consumer<HarnessEvent>> listeners = new CopyOnWriteArrayList<>();

    /** 注册监听器，返回取消订阅句柄。 */
    public Runnable subscribe(Consumer<HarnessEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 发布事件：同步调用所有监听器；单个监听器异常不影响其他监听器与事件序号。 */
    public void publish(HarnessEvent.Builder builder) {
        HarnessEvent event = builder.build(seq.incrementAndGet());
        for (Consumer<HarnessEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 审计监听器失败不应中断执行主流程；错误在各自监听器内记录。
            }
        }
    }

    public List<Consumer<HarnessEvent>> listeners() {
        return List.copyOf(listeners);
    }

    public long currentSeq() {
        return seq.get();
    }

    /** 按运行 ID 收集事件的简单容器，供测试与 Run Inspector 使用。 */
    public static class Collector implements Consumer<HarnessEvent> {
        private final Map<String, List<HarnessEvent>> byRun = new ConcurrentHashMap<>();
        private final List<HarnessEvent> all = new CopyOnWriteArrayList<>();

        @Override
        public void accept(HarnessEvent event) {
            all.add(event);
            if (event.runId() != null) {
                byRun.computeIfAbsent(event.runId().value(), k -> new CopyOnWriteArrayList<>()).add(event);
            }
        }

        public List<HarnessEvent> all() {
            return List.copyOf(all);
        }

        public List<HarnessEvent> ofRun(String runId) {
            return List.copyOf(byRun.getOrDefault(runId, List.of()));
        }
    }
}
