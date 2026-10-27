package cn.aigc.dit.server.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresWorkflowRunLockTest {

    @Test
    void limitsHeldLockConnectionsToPoolSizeMinusTwo() throws Exception {
        HikariDataSource source = lockablePool(4);
        PostgresWorkflowRunLock lock = new PostgresWorkflowRunLock(source);

        WorkflowRunLock.Handle first = lock.tryAcquire(1L);
        WorkflowRunLock.Handle second = lock.tryAcquire(2L);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(lock.tryAcquire(3L));
        verify(source, times(2)).getConnection();

        first.close();
        WorkflowRunLock.Handle third = lock.tryAcquire(3L);
        assertNotNull(third);
        third.close();
        second.close();
    }

    @Test
    void concurrentAttemptsCannotExceedConnectionReserve() throws Exception {
        HikariDataSource source = lockablePool(4);
        PostgresWorkflowRunLock lock = new PostgresWorkflowRunLock(source);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<WorkflowRunLock.Handle> held = new ArrayList<>();
        try {
            List<Future<WorkflowRunLock.Handle>> attempts = new ArrayList<>();
            for (long runId = 1; runId <= 8; runId++) {
                long id = runId;
                attempts.add(workers.submit(() -> {
                    start.await();
                    return lock.tryAcquire(id);
                }));
            }
            start.countDown();
            for (Future<WorkflowRunLock.Handle> attempt : attempts) {
                WorkflowRunLock.Handle handle = attempt.get();
                if (handle != null) {
                    held.add(handle);
                }
            }
            assertEquals(2, held.size());
        } finally {
            for (WorkflowRunLock.Handle handle : held) {
                handle.close();
            }
            workers.shutdownNow();
        }
    }

    @Test
    void failedBorrowRestoresPermit() throws Exception {
        HikariDataSource source = lockablePool(3);
        Connection connection = source.getConnection();
        when(source.getConnection()).thenThrow(new SQLException("pool unavailable")).thenReturn(connection);
        PostgresWorkflowRunLock lock = new PostgresWorkflowRunLock(source);

        assertThrows(IllegalStateException.class, () -> lock.tryAcquire(1L));
        WorkflowRunLock.Handle recovered = lock.tryAcquire(2L);
        assertNotNull(recovered);
        assertNull(lock.tryAcquire(3L));
        recovered.close();
        assertNotNull(lock.tryAcquire(4L));
    }

    @Test
    void invalidRunIdDoesNotConsumePermit() throws Exception {
        PostgresWorkflowRunLock lock = new PostgresWorkflowRunLock(lockablePool(3));

        assertThrows(NullPointerException.class, () -> lock.tryAcquire(null));
        WorkflowRunLock.Handle held = lock.tryAcquire(1L);
        assertNotNull(held);
        assertNull(lock.tryAcquire(2L));
        held.close();
    }

    @Test
    void acquiredSessionLockIsReleasedIfStatementCloseFails() throws Exception {
        HikariDataSource source = lockablePool(3);
        Connection connection = source.getConnection();
        PreparedStatement acquire = connection.prepareStatement("SELECT pg_try_advisory_lock(?)");
        doThrow(new SQLException("statement close failed")).when(acquire).close();

        assertThrows(IllegalStateException.class, () -> new PostgresWorkflowRunLock(source).tryAcquire(7L));

        verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)");
        verify(connection).close();
    }

    @Test
    void holdsSessionLockUntilHandleClosesAndUnlocksSameKey() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquired = mock(ResultSet.class);
        ResultSet released = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(release.executeQuery()).thenReturn(released);
        when(acquired.next()).thenReturn(true);
        when(acquired.getBoolean(1)).thenReturn(true);
        when(released.next()).thenReturn(true);
        when(released.getBoolean(1)).thenReturn(true);

        WorkflowRunLock.Handle handle = new PostgresWorkflowRunLock(source).tryAcquire(42L);

        assertNotNull(handle);
        verify(connection, org.mockito.Mockito.never()).close();
        handle.close();
        org.mockito.ArgumentCaptor<Long> acquiredKey = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.ArgumentCaptor<Long> releasedKey = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(acquire).setLong(org.mockito.ArgumentMatchers.eq(1), acquiredKey.capture());
        verify(release).setLong(org.mockito.ArgumentMatchers.eq(1), releasedKey.capture());
        assertEquals(acquiredKey.getValue(), releasedKey.getValue());
        verify(connection).close();
    }

    private static HikariDataSource lockablePool(int maximumPoolSize) throws Exception {
        HikariDataSource source = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquired = mock(ResultSet.class);
        ResultSet released = mock(ResultSet.class);
        when(source.getMaximumPoolSize()).thenReturn(maximumPoolSize);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(release.executeQuery()).thenReturn(released);
        when(acquired.next()).thenReturn(true);
        when(acquired.getBoolean(1)).thenReturn(true);
        when(released.next()).thenReturn(true);
        when(released.getBoolean(1)).thenReturn(true);
        return source;
    }
}
