package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TaskPumpTest {
    @Test void longTaskDefersRemainingWorkWithoutDroppingOrReordering() {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var clock = new AtomicLong();
        var order = new StringBuilder();
        queue.add(() -> { order.append('a'); clock.set(2_000_000); });
        queue.add(() -> order.append('b'));
        assertEquals(1, TaskPump.drain(queue, 8, 1_000_000, clock::get));
        assertEquals("a", order.toString());
        assertEquals(1, queue.size());
        assertEquals(1, TaskPump.drain(queue, 8, 1_000_000, clock::get));
        assertEquals("ab", order.toString());
    }
    @Test void selfSchedulingWorkCannotMonopolizeOneFrame() {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var count = new AtomicInteger();
        Runnable[] loop = new Runnable[1];
        loop[0] = () -> { count.incrementAndGet(); queue.add(loop[0]); };
        queue.add(loop[0]);
        assertEquals(8, TaskPump.drain(queue, 8, 1_000_000, () -> 0L));
        assertEquals(8, count.get());
        assertEquals(1, queue.size());
    }
}
