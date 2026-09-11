package com.rr.client.vpn;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real JVM fixture for the production native registration and owner callback. */
public final class HevTunnelNative {
    private static native void TProxySetAppRouting(boolean enabled);
    private static native boolean TProxyStartService(String path, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();
    private static native boolean isNativeThreadStack();
    private static native long invalidTokens();

    private static final long TOKEN = (10428L << 32) | (65534L << 16) | 20809L;
    private static final AtomicInteger callbacks = new AtomicInteger();
    private static final AtomicInteger wrongStacks = new AtomicInteger();
    private static CountDownLatch slowEntered, slowRelease, slowFinished;

    public static long resolveRoute(int protocol, String source, int sourcePort,
                                    String destination, int destinationPort) {
        callbacks.incrementAndGet();
        if (!isNativeThreadStack()) {
            wrongStacks.incrementAndGet();
            throw new AssertionError("Owner JNI ran on a HEV coroutine stack");
        }
        if (sourcePort == 65002 || sourcePort == 65003) {
            slowEntered.countDown();
            try {
                require(slowRelease.await(5, TimeUnit.SECONDS), "Slow callback release timed out");
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            } finally {
                slowFinished.countDown();
            }
            // Deliberately different outlet/generation: a late answer cannot enter a restarted tunnel.
            return (10428L << 32) | (123L << 16) | 20810L;
        }
        if (sourcePort == 65001) throw new IllegalStateException("Expected owner lookup failure");
        require(protocol == 6 || protocol == 17, "Original protocol lost");
        require(source.equals("198.18.0.1") && sourcePort == 32100, "Original source lost");
        require(destination.equals("203.0.113.21") && destinationPort == 443,
                "Original destination lost");
        return TOKEN;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long[] joinWorker() throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (TProxyIsRunning() && System.nanoTime() < deadline) Thread.sleep(2);
        require(!TProxyIsRunning(), "Native worker did not finish within bound");
        require(TProxyStopService(), "Native worker join failed");
        return TProxyGetStats();
    }

    private static void requireStats(long[] stats) {
        require(stats != null && stats.length == 4 && stats[0] == 2 && stats[1] == 2
                && stats[2] == 0 && stats[3] == 2,
                "Native token/stack result mismatch: " + java.util.Arrays.toString(stats));
    }

    private static void testSlowOwner(boolean stopDuringLookup) throws Exception {
        slowEntered = new CountDownLatch(1);
        slowRelease = new CountDownLatch(1);
        slowFinished = new CountDownLatch(1);
        TProxySetAppRouting(true);
        require(TProxyStartService(stopDuringLookup ? "slow-stop" : "slow-timeout", -1),
                "Slow callback service failed to start");
        try {
            require(slowEntered.await(2, TimeUnit.SECONDS), "Slow callback did not enter JVM");
            long started = System.nanoTime();
            if (stopDuringLookup) {
                require(TProxyStopService(), "Stop failed while owner callback blocked");
                require(System.nanoTime() - started < 1_500_000_000L,
                        "HEV stop waited for blocked owner callback");
                requireStats(TProxyGetStats());
            } else {
                requireStats(joinWorker());
                require(System.nanoTime() - started < 2_000_000_000L,
                        "HEV owner lookup did not time out");
            }
            int before = callbacks.get();
            require(TProxyStartService("busy", -1), "Restart failed while old owner still pending");
            requireStats(joinWorker());
            require(callbacks.get() == before, "Restart spawned another blocked owner thread");
        } finally {
            slowRelease.countDown();
        }
        require(slowFinished.await(2, TimeUnit.SECONDS), "Slow callback did not finish");
        long deadline = System.nanoTime() + 2_000_000_000L;
        boolean recovered = false;
        do {
            require(TProxyStartService("recovery", -1), "Recovery service failed to start");
            long[] stats = joinWorker();
            require(invalidTokens() == 0, "A late callback from the old runtime was accepted");
            recovered = stats[2] == 0;
            if (!recovered) Thread.sleep(2);
        } while (!recovered && System.nanoTime() < deadline);
        require(recovered, "Owner dispatcher did not recover after delayed callback");
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        int starts = 0;
        int coroutineChecks = 0;
        for (int iteration = 0; iteration < 8; iteration++) {
            for (String mode : new String[] { "success", "exception", "disabled" }) {
                require(TProxyStopService(), "Pre-start stop failed");
                int before = callbacks.get();
                TProxySetAppRouting(!mode.equals("disabled"));
                require(TProxyStartService(mode, -1), "Native service failed to start");
                starts++;
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (TProxyIsRunning() && System.nanoTime() < deadline) Thread.sleep(2);
                require(!TProxyIsRunning(), "Native worker did not finish");
                require(TProxyStopService(), "Native worker join failed");
                long[] stats = TProxyGetStats();
                require(stats != null && stats.length == 4, "Production JNI stats signature failed");
                require(stats[0] == 2 && stats[1] == 2 && stats[2] == 0,
                        "Native token/exception result mismatch: " + java.util.Arrays.toString(stats));
                require(stats[3] == 2, "Fixture did not run on actual HEV coroutine stacks");
                coroutineChecks += stats[3];
                require(callbacks.get() - before == (mode.equals("disabled") ? 0 : 2),
                        "Disabled routing called Java, or enabled callback count incorrect");
                require(wrongStacks.get() == 0, "Owner JNI ran outside pthread stack");
            }
        }
        testSlowOwner(false);
        testSlowOwner(true);
        TProxySetAppRouting(false);
        require(TProxyStopService(), "Final stop failed");
        System.out.println("RRBOX_HEV_JNI_OK baseline_starts=" + starts + " total_callbacks=" + callbacks.get()
                + " baseline_coroutine_checks=" + coroutineChecks + " wrong_stacks=" + wrongStacks.get());
        System.out.println("RRBOX_HEV_JNI_SLOW_OK timeout=passed stop=passed stale_epoch=passed");
    }
}
