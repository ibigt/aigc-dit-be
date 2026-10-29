package cn.aigc.dit.storage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 对象存储连续超时后短暂熔断，避免每个封面请求都干等超时、占满页面连接。
 * 熔断窗口结束后只放行一个探测请求。
 */
final class S3AvailabilityGuard {

    static final int FAILURES_TO_OPEN = 3;
    static final long OPEN_FOR_MS = 15_000L;

    private final LongSupplier clock;
    private final int failuresToOpen;
    private final long openForMs;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();
    private final AtomicBoolean probe = new AtomicBoolean();

    S3AvailabilityGuard() {
        this(System::currentTimeMillis, FAILURES_TO_OPEN, OPEN_FOR_MS);
    }

    S3AvailabilityGuard(LongSupplier clock, int failuresToOpen, long openForMs) {
        this.clock = clock;
        this.failuresToOpen = failuresToOpen;
        this.openForMs = openForMs;
    }

    void enter() {
        long until = openUntil.get();
        long now = clock.getAsLong();
        if (until > now) {
            throw unavailable();
        }
        if (until > 0 && !probe.compareAndSet(false, true)) {
            throw unavailable();
        }
    }

    /** 存储有响应（成功或确认不存在）时关闭熔断。 */
    void reachable() {
        failures.set(0);
        openUntil.set(0);
        probe.set(false);
    }

    /** @return 这次失败是否刚刚打开熔断 */
    boolean timeout() {
        boolean wasProbe = probe.getAndSet(false);
        if (wasProbe || failures.incrementAndGet() >= failuresToOpen) {
            openUntil.set(clock.getAsLong() + openForMs);
            failures.set(0);
            return true;
        }
        return false;
    }

    private static BizException unavailable() {
        return new BizException(ErrorCode.UNAVAILABLE, "对象存储暂不可用");
    }
}
