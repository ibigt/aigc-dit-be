package cn.aigc.dit.server.service;

/** Serializes advancement of one workflow run across application instances. */
public interface WorkflowRunLock {
    Handle tryAcquire(Long runId);

    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
