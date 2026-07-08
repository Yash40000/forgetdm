package io.forgetdm.virtualization;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Live-progress tracker for long virtualization operations (dSource snapshot, VDB provision, refresh/rewind).
 * Work runs on a daemon executor; the running code reports coarse stages via {@link #stage} (routed to the
 * current thread's operation through a ThreadLocal, so provider methods don't need signature changes). The UI
 * polls {@link #view} for a live stage list and — crucially — a persisted final status/error (so a failure no
 * longer vanishes with the toast).
 */
@Component
public class VirtOps {

    private static final int MAX_KEEP = 50;

    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private final Deque<String> order = new ConcurrentLinkedDeque<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, Runnable> cancelHooks = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ExecutorService exec = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "forgetdm-virt-op");
        t.setDaemon(true);
        return t;
    });
    private final ThreadLocal<String> current = new ThreadLocal<>();

    private static final class Op {
        final String id, kind, label;
        volatile String status = "RUNNING", message = "Starting…", error;
        volatile Object result;
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt;
        final List<Stage> stages = new CopyOnWriteArrayList<>();
        Op(String id, String kind, String label) { this.id = id; this.kind = kind; this.label = label; }
    }

    private static final class Stage {
        final String name;
        volatile String status = "RUNNING";
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt;
        Stage(String name) { this.name = name; }
    }

    /** Run {@code work} asynchronously with progress tracking; returns the opId immediately. */
    public String run(String kind, String label, Supplier<Object> work) {
        String id = UUID.randomUUID().toString();
        Op op = new Op(id, kind, label);
        ops.put(id, op);
        order.addLast(id);
        prune();
        Future<?> f = exec.submit(() -> {
            current.set(id);
            try {
                Object r = work.get();
                closeLastStage(op, "DONE");
                op.result = r;
                op.status = "DONE";
                op.message = "Completed";
            } catch (Exception e) {
                boolean wasCancel = cancelled.contains(id);
                closeLastStage(op, wasCancel ? "CANCELLED" : "FAILED");
                if (wasCancel) {
                    op.status = "CANCELLED";
                    op.message = "Cancelled";
                } else {
                    op.status = "FAILED";
                    op.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    op.message = "Failed";
                }
            } finally {
                op.finishedAt = System.currentTimeMillis();
                current.remove();
                futures.remove(id);
                cancelHooks.remove(id);
            }
        });
        futures.put(id, f);
        return id;
    }

    /** Register a best-effort cleanup to run if the current operation is cancelled (e.g. kill the engine container). */
    public void onCancel(Runnable hook) {
        String id = current.get();
        if (id != null && hook != null) cancelHooks.put(id, hook);
    }

    /** Request cancellation: run the cleanup hook (e.g. docker kill) and interrupt the worker. */
    public boolean cancel(String id) {
        Op op = ops.get(id);
        if (op == null || !"RUNNING".equals(op.status)) return false;
        cancelled.add(id);
        op.message = "Cancelling…";
        Runnable hook = cancelHooks.get(id);
        if (hook != null) {
            Thread t = new Thread(hook, "forgetdm-virt-cancel");
            t.setDaemon(true);
            t.start();
        }
        Future<?> fut = futures.get(id);
        if (fut != null) fut.cancel(true);
        return true;
    }

    /** Mark a new stage on the current thread's operation (no-op when not running inside {@link #run}). */
    public void stage(String name) {
        String id = current.get();
        if (id == null) return;
        Op op = ops.get(id);
        if (op == null) return;
        closeLastStage(op, "DONE");
        op.stages.add(new Stage(name));
        op.message = name;
    }

    public Map<String, Object> view(String id) {
        Op op = ops.get(id);
        return op == null ? null : toMap(op);
    }

    public List<Map<String, Object>> recent() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : order) { Op op = ops.get(id); if (op != null) out.add(toMap(op)); }
        Collections.reverse(out);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static void closeLastStage(Op op, String status) {
        if (op.stages.isEmpty()) return;
        Stage last = op.stages.get(op.stages.size() - 1);
        if ("RUNNING".equals(last.status)) { last.status = status; last.finishedAt = System.currentTimeMillis(); }
    }

    private void prune() {
        while (order.size() > MAX_KEEP) {
            String old = order.pollFirst();
            if (old != null) ops.remove(old);
        }
    }

    private Map<String, Object> toMap(Op op) {
        long end = op.finishedAt > 0 ? op.finishedAt : System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", op.id);
        m.put("kind", op.kind);
        m.put("label", op.label);
        m.put("status", op.status);
        m.put("message", op.message);
        if (op.error != null) m.put("error", op.error);
        if (op.result != null) m.put("result", op.result);
        m.put("startedAt", Instant.ofEpochMilli(op.startedAt).toString());
        if (op.finishedAt > 0) m.put("finishedAt", Instant.ofEpochMilli(op.finishedAt).toString());
        m.put("elapsedMs", end - op.startedAt);
        List<Map<String, Object>> st = new ArrayList<>();
        for (Stage s : op.stages) {
            long se = s.finishedAt > 0 ? s.finishedAt : System.currentTimeMillis();
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", s.name);
            sm.put("status", s.status);
            sm.put("elapsedMs", se - s.startedAt);
            st.add(sm);
        }
        m.put("stages", st);
        return m;
    }
}
