/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * GitExecutorTest. a task waiting for the user keeps the git thread serving the other tasks.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
class GitExecutorTest {

    @Test
    void awaitRunsNestedTasks() throws Exception {
        GitExecutor exec = new GitExecutor(Throwable::printStackTrace);
        List<String> order = new CopyOnWriteArrayList<>();
        CompletableFuture<String> user = new CompletableFuture<>();
        CountDownLatch waiting = new CountDownLatch(1), finished = new CountDownLatch(1);
        exec.run(() -> {
            order.add("wait");
            waiting.countDown();
            order.add("got " + exec.await(user));
        }, null);
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        // submitted while waiting: runs on the git thread before the waiting task goes on
        exec.run(() -> {
            order.add("nested " + Thread.currentThread().getName());
            user.complete("ok");
        }, null);
        // runs inside the wait or after it, still on the git thread
        exec.run(() -> { order.add("later " + Thread.currentThread().getName()); finished.countDown(); }, null);
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(List.of("wait", "nested git", "got ok"), order.stream().filter(x -> !x.startsWith("later")).toList());
        assertTrue(order.contains("later git"));
        exec.shutdown();
    }
}
