package cn.aigc.dit.storage;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3AvailabilityGuardTest {

    @Test
    void opensAfterRepeatedTimeoutsAndFailsFastUntilTheWindowEnds() {
        AtomicLong now = new AtomicLong(1_000);
        S3AvailabilityGuard guard = new S3AvailabilityGuard(now::get, 3, 15_000);

        assertFalse(guard.timeout());
        assertFalse(guard.timeout());
        assertTrue(guard.timeout());

        BizException blocked = assertThrows(BizException.class, guard::enter);
        assertEquals(ErrorCode.UNAVAILABLE, blocked.getCode());

        now.set(16_000);
        assertDoesNotThrow(guard::enter);
        BizException whileProbing = assertThrows(BizException.class, guard::enter);
        assertEquals(ErrorCode.UNAVAILABLE, whileProbing.getCode());

        assertTrue(guard.timeout());
        now.set(31_000);
        guard.enter();
        guard.reachable();
        assertDoesNotThrow(guard::enter);
        assertDoesNotThrow(guard::enter);
    }

    @Test
    void reachableStorageClosesTheCircuit() {
        AtomicLong now = new AtomicLong(0);
        S3AvailabilityGuard guard = new S3AvailabilityGuard(now::get, 3, 15_000);
        guard.timeout();
        guard.timeout();
        guard.timeout();
        now.set(15_000);
        guard.enter();
        guard.reachable();
        assertDoesNotThrow(guard::enter);
    }
}
