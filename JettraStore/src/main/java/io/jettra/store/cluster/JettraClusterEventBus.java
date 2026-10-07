package io.jettra.store.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Bus de eventos en tiempo real para el clúster de JettraStore.
 * Almacena los eventos más recientes en memoria circular y los despacha concurrentemente
 * a los suscriptores conectados (Shell, Driver, Police3D, REST SSE).
 */
public final class JettraClusterEventBus {

    private static final JettraClusterEventBus INSTANCE = new JettraClusterEventBus();
    private static final int MAX_HISTORY = 500;

    private final Deque<ClusterLiveEvent> history = new ArrayDeque<>(MAX_HISTORY);
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Consumer<ClusterLiveEvent>> listeners = new CopyOnWriteArrayList<>();

    private JettraClusterEventBus() {}

    public static JettraClusterEventBus getInstance() {
        return INSTANCE;
    }

    public void publish(ClusterLiveEvent event) {
        if (event == null) return;
        lock.lock();
        try {
            if (history.size() >= MAX_HISTORY) {
                history.pollFirst();
            }
            history.addLast(event);
        } finally {
            lock.unlock();
        }

        // Notificación asíncrona no bloqueante a los oyentes usando Virtual Threads
        for (Consumer<ClusterLiveEvent> listener : listeners) {
            Thread.ofVirtual().name("jettra-live-event-dispatch").start(() -> {
                try {
                    listener.accept(event);
                } catch (Exception ignored) {}
            });
        }
    }

    public void publish(String type, String sourceNodeId, String targetNodeId, String message, String details) {
        publish(new ClusterLiveEvent(
            System.currentTimeMillis(),
            type,
            sourceNodeId != null ? sourceNodeId : "local",
            targetNodeId != null ? targetNodeId : "cluster",
            message != null ? message : "",
            details != null ? details : ""
        ));
    }

    public List<ClusterLiveEvent> getRecentEvents(int limit) {
        lock.lock();
        try {
            int take = Math.min(Math.max(1, limit), history.size());
            List<ClusterLiveEvent> list = new ArrayList<>(take);
            int skip = history.size() - take;
            int current = 0;
            for (ClusterLiveEvent ev : history) {
                if (current >= skip) {
                    list.add(ev);
                }
                current++;
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

    public void subscribe(Consumer<ClusterLiveEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(Consumer<ClusterLiveEvent> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void clear() {
        lock.lock();
        try {
            history.clear();
        } finally {
            lock.unlock();
        }
    }
}
