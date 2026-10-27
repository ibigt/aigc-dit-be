package cn.aigc.dit.server.model.resp;

import java.util.List;

/** Current saved-graph runtime, assembled from bounded, owned run history without changing the graph. */
public record WorkflowSnapshotResp(Long workflowId, Long latestRunId,
                                   List<WorkflowRunResp.StepResp> steps, List<RunState> activeRuns) {
    public record RunState(Long id, String status) { }
}
