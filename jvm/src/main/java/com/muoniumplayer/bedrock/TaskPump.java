package com.muoniumplayer.bedrock;

import java.util.Queue;
import java.util.function.LongSupplier;

/** Yield between tasks so background completions cannot consume a whole frame. */
final class TaskPump {
    static int drain(Queue<Runnable> queue, int maximum, long budgetNanos, LongSupplier clock) {
        long start = clock.getAsLong();
        int completed = 0;
        while (completed < maximum) {
            Runnable task = queue.poll();
            if (task == null) break;
            task.run();
            completed++;
            if (clock.getAsLong() - start >= budgetNanos) break;
        }
        return completed;
    }
    private TaskPump() {}
}
