package cn.aigc.dit.server.service;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PostgresWorkflowRunLock implements WorkflowRunLock {
    private static final long WORKFLOW_NAMESPACE = 0x57464C4F57000000L;

    private final DataSource dataSource;
    private final Semaphore permits;

    public PostgresWorkflowRunLock(DataSource dataSource) {
        this.dataSource = dataSource;
        int capacity = dataSource instanceof HikariDataSource hikari
                ? Math.max(1, hikari.getMaximumPoolSize() - 2) : 1;
        this.permits = new Semaphore(capacity);
    }

    @Override
    public Handle tryAcquire(Long runId) {
        Objects.requireNonNull(runId, "runId");
        if (!permits.tryAcquire()) {
            return null;
        }
        long key = WORKFLOW_NAMESPACE ^ runId;
        Connection connection = null;
        boolean acquired = false;
        boolean handedOff = false;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        return null;
                    }
                    acquired = true;
                }
            }
            Connection lockedConnection = connection;
            AtomicBoolean closed = new AtomicBoolean();
            handedOff = true;
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    try {
                        release(lockedConnection, key);
                    } finally {
                        permits.release();
                    }
                }
            };
        } catch (SQLException | RuntimeException ex) {
            try {
                if (acquired) {
                    release(connection, key);
                } else {
                    closeQuietly(connection);
                }
            } catch (RuntimeException cleanupError) {
                ex.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("无法获取工作流运行锁", ex);
        } finally {
            if (!handedOff) {
                permits.release();
            }
        }
    }

    private static void release(Connection connection, long key) {
        try {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException("工作流运行锁未被当前连接持有");
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("无法释放工作流运行锁", ex);
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The pool will discard a broken connection.
            }
        }
    }
}
