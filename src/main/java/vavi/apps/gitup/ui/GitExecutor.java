/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "git");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<Throwable> defaultErrorHandler;

    public GitExecutor(Consumer<Throwable> defaultErrorHandler) {
        this.defaultErrorHandler = defaultErrorHandler;
    }

    /** runs task on the git thread, then onDone on the EDT */
    public <T> void submit(Callable<T> task, Consumer<T> onDone) {
        submit(task, onDone, defaultErrorHandler);
    }

    public <T> void submit(Callable<T> task, Consumer<T> onDone, Consumer<Throwable> onError) {
        executor.execute(() -> {
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

    public void shutdown() {
        executor.shutdown();
    }
}
