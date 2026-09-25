/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import static java.lang.System.getLogger;


/**
 * the single thread every libgit2 / GitUpKit call runs on (a libgit2 repository
 * handle is not thread safe). results come back on the EDT.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class GitExecutor {

    private static final System.Logger logger = getLogger(GitExecutor.class.getName());

    private Thread thread;

    /** a single thread, its queue is taken over by {@link #await} */
    private final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "git");
        t.setDaemon(true);
        thread = t;
        return t;
    });

    /** tasks submitted while a task on the git thread {@link #await awaits} the user, null otherwise */
    private Deque<Runnable> nested;

    private final Consumer<Throwable> defaultErrorHandler;

    public GitExecutor(Consumer<Throwable> defaultErrorHandler) {
        this.defaultErrorHandler = defaultErrorHandler;
    }

    /** runs task on the git thread, then onDone on the EDT */
    public <T> void submit(Callable<T> task, Consumer<T> onDone) {
        submit(task, onDone, defaultErrorHandler);
    }

    public <T> void submit(Callable<T> task, Consumer<T> onDone, Consumer<Throwable> onError) {
        execute(() -> {
            try {
                T result = task.call();
                if (onDone != null) SwingUtilities.invokeLater(() -> onDone.accept(result));
            } catch (Throwable t) {
                logger.log(System.Logger.Level.DEBUG, t.getMessage(), t);
                SwingUtilities.invokeLater(() -> onError.accept(t));
            }
        });
    }

    /** runs an action without result on the git thread, then onDone on the EDT */
    public void run(Runnable task, Runnable onDone) {
        submit(() -> { task.run(); return null; }, x -> { if (onDone != null) onDone.run(); });
    }

    private void execute(Runnable r) {
        synchronized (this) {
            if (nested != null) {
                nested.add(r);
                notifyAll();
                return;
            }
        }
        executor.execute(r);
    }

    /**
     * called by a task on the git thread that waits for the user (e.g. resolving conflicts in the middle of
     * a history rewrite): tasks submitted meanwhile run here, on the git thread, until the future is done.
     *
     * @return the value of the future
     */
    public <T> T await(CompletableFuture<T> future) {
        if (Thread.currentThread() != thread) throw new IllegalStateException("not on the git thread");
        synchronized (this) {
            if (nested != null) throw new IllegalStateException("already waiting");
            nested = new ArrayDeque<>();
            // submitted before the wait began, they would be queued behind this task (deadlock when the future depends on them)
            executor.getQueue().drainTo(nested);
        }
        future.whenComplete((v, t) -> { synchronized (this) { notifyAll(); } });
        try {
            while (true) {
                Runnable r;
                synchronized (this) {
                    while ((r = nested.poll()) == null && !future.isDone()) wait();
                }
                if (r == null) return future.get();
                r.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            synchronized (this) {
                nested.forEach(executor::execute); // submitted too late, they run after the waiting task
                nested = null;
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
