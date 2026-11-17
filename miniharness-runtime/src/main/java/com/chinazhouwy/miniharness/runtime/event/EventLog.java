package com.chinazhouwy.miniharness.runtime.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件历史日志：内存追加式，供 Run Inspector 回放与 SSE 重放。
 * 与 EventBus 分离——总线负责扇出，日志负责留存。
 */
public class EventLog implements java.util.function.Consumer<HarnessEvent> {

    private final List<HarnessEvent> events = new ArrayList<>();

    @Override
    public synchronized void accept(HarnessEvent event) {
        events.add(event);
    }

    public synchronized List<HarnessEvent> all() {
        return List.copyOf(events);
    }

    public synchronized List<HarnessEvent> byRun(String runId) {
        List<HarnessEvent> matched = new ArrayList<>();
        for (HarnessEvent event : events) {
            if (event.runId() != null && event.runId().value().equals(runId)) {
                matched.add(event);
            }
        }
        return matched;
    }

    public synchronized List<HarnessEvent> since(long seq) {
        List<HarnessEvent> matched = new ArrayList<>();
        for (HarnessEvent event : events) {
            if (event.seq() > seq) {
                matched.add(event);
            }
        }
        return matched;
    }

    public synchronized int size() {
        return events.size();
    }
}
