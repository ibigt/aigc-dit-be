package cn.aigc.dit.server.service;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.entity.WorkflowRun;
import cn.aigc.dit.server.entity.Workflow;
import cn.aigc.dit.server.generate.GenerateTaskPublisher;
import cn.aigc.dit.server.mapper.*;
import cn.aigc.dit.server.model.req.WorkflowRunReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.workflow.WorkflowFilmComposer;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real planner/start/generation normalization; in-memory persistence and wallet at external boundaries. */
class WorkflowRunLifecycleTest {
    @AfterEach void clearUser() { UserContext.clear(); }

    @Test
    void unsavedGraphRetryUsesOnlyExplicitOwnedPriorRunAndRejectsForeignPointer() {
        Harness h = new Harness();
        var req = h.request("target", "source", 0); req.setWorkflowId(null);
        var first = h.runs.start(req);
        h.succeedTasks(); h.runs.getMine(first.getId()); h.succeedTasks(); h.runs.getMine(first.getId());
        req.setPreviousRunId(first.getId());
        var retry = h.runs.start(req);
        assertEquals("SUCCEEDED", retry.getSteps().get(0).getStatus());
        assertEquals(3, h.tasks.size());
        h.stored.get(first.getId()).setUserId(8L);
        assertThrows(cn.aigc.dit.common.exception.BizException.class, () -> h.runs.start(req));
        assertEquals(3, h.tasks.size());
    }

    @Test
    void latestRunLookupIsUserOwnedAndNewestAndAdvancesActiveRuns() {
        Harness h = new Harness();
        var first = h.runs.start(h.request("target", "source", 0));
        h.succeedTasks(); h.runs.getMine(first.getId());
        h.succeedTasks(); h.runs.getMine(first.getId());
        var second = h.runs.start(h.request("target", "source", 0));
        WorkflowRun foreign = Harness.copy(h.stored.get(first.getId())); foreign.setUserId(8L); foreign.setId(1000L); h.stored.put(1000L, foreign);
        var response = h.runs.latestMine(99L);
        assertNotNull(response);
        assertEquals(second.getId(), response.getId());
        assertEquals("RUNNING", response.getStatus());
        assertEquals(3, h.tasks.size());
        h.succeedTasks();
        response = h.runs.latestMine(99L);
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals("users/7/outputs/3/0.mp4", response.getSteps().get(1).getOutputs().get(0).getStorageKey());
        h.stored.values().forEach(run -> run.setUserId(8L));
        assertNull(h.runs.latestMine(99L));
    }

    @Test
    void retryAndComposeOnlyReuseUnchangedOwnedPredecessorsWithoutSubmittingOrChargingAgain() {
        Harness h = new Harness();
        var initial = h.runs.start(h.request("target", "source", 0));
        h.succeedTasks();
        h.runs.getMine(initial.getId());
        h.succeedTasks();
        h.runs.getMine(initial.getId());
        assertEquals(2, h.tasks.size()); assertEquals(15, h.charged.get());

        var retry = h.runs.start(h.request("target", "source", 0));
        assertEquals("SUCCEEDED", retry.getSteps().get(0).getStatus());
        assertEquals("RUNNING", retry.getSteps().get(1).getStatus());
        assertEquals(3, h.tasks.size()); assertEquals(20, h.charged.get());
        h.succeedTasks(); h.runs.getMine(retry.getId());

        var film = h.runs.start(h.request("edit", "source", 0));
        assertEquals("SUCCEEDED", film.getStatus());
        assertEquals(3, h.tasks.size()); assertEquals(20, h.charged.get());
        assertEquals(List.of("users/7/outputs/1/0.mp4", "users/7/outputs/3/0.mp4"), h.composedKeys);
        assertEquals(0L, film.getSteps().get(0).getCreditCost());
        assertNotNull(h.stored.get(film.getId()).getSteps().get(0).get("inputFingerprint"));
    }

    @Test
    void changedPromptAndSelectedDependencyInvalidateOnlyAffectedChainAndIgnoreClientOutputs() {
        Harness h = new Harness();
        var initial = h.runs.start(h.request("edit", "source", 0));
        h.succeedTasks(); h.runs.getMine(initial.getId());
        h.succeedTasks(); h.runs.getMine(initial.getId());
        assertEquals(2, h.tasks.size());
        var selected = h.runs.start(h.request("edit", "source", 1));
        assertEquals("SUCCEEDED", selected.getSteps().get(0).getStatus());
        assertEquals("RUNNING", selected.getSteps().get(1).getStatus());
        assertEquals(3, h.tasks.size());
        assertEquals("users/7/outputs/1/1.mp4", ((Map<?, ?>)((List<?>) h.tasks.get(3L).getParams().get("references")).get(0)).get("storageKey"));
        h.succeedTasks(); h.runs.getMine(selected.getId());
        var changed = h.runs.start(h.request("edit", "different prompt", 1));
        assertEquals("RUNNING", changed.getSteps().get(0).getStatus());
        assertEquals("PENDING", changed.getSteps().get(1).getStatus());
        assertEquals(4, h.tasks.size());
    }

    @Test
    void otherUsersRunsAndNonDurableOutputsAreNeverReuseCandidates() {
        Harness h = new Harness();
        var first = h.runs.start(h.request("target", "source", 0));
        h.succeedTasks(); h.runs.getMine(first.getId());
        h.succeedTasks(); h.runs.getMine(first.getId());
        h.stored.get(first.getId()).setUserId(8L);
        h.runs.start(h.request("target", "source", 0));
        assertEquals(3, h.tasks.size());
        h.stored.get(first.getId()).setUserId(7L);
        h.stored.remove(2L);
        h.stored.get(first.getId()).getSteps().get(0).put("outputs", List.of(Map.of("type", "video", "storageKey", "blob:fake")));
        var rejected = h.runs.start(h.request("target", "source", 0));
        assertEquals("RUNNING", rejected.getSteps().get(0).getStatus());
        assertEquals(4, h.tasks.size());
    }

    @Test
    void realStartComposesSameSelectedBAQueueAsFrontendThroughReachableBridge() {
        Harness h = new Harness();
        WorkflowRunReq req = new WorkflowRunReq(); req.setWorkflowId(99L); req.setModelId(2L); req.setNodeId("edit");
        req.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "a", "copies", 2)),
                Harness.node("b", "VideoGenerator", Map.of("prompt", "b", "copies", 2, "selectedOutputIndex", 1)),
                Harness.node("bridge", "Reroute", Map.of()), Harness.node("edit", "VideoEditing", Map.of())),
                "edges", List.of(Harness.edge("b", "bridge"), Harness.edge("a", "bridge"), Harness.edge("bridge", "edit"))));
        var started = h.runs.start(req);
        h.tasks.values().forEach(task -> { task.setStatus("SUCCEEDED");
            task.setOutputKeys(List.of("users/7/" + task.getPrompt() + "-0.mp4", "users/7/" + task.getPrompt() + "-1.mp4")); });
        var result = h.runs.getMine(started.getId());
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(List.of("users/7/b-1.mp4", "users/7/a-0.mp4"), h.composedKeys);
    }

    @Test
    void separatelyGeneratedBranchesReuseNewestMatchingHistoryWhenComposing() {
        for (boolean saved : List.of(true, false)) {
            Harness h = new Harness();
            var req = h.independentRequest("a", saved);
            var a = h.runs.start(req);
            h.succeedTasks(); h.runs.getMine(a.getId());
            req.setNodeId("b"); if (!saved) req.setPreviousRunId(a.getId());
            var b = h.runs.start(req);
            h.succeedTasks(); h.runs.getMine(b.getId());
            req.setNodeId("b"); if (!saved) req.setPreviousRunId(b.getId());
            var newerB = h.runs.start(req);
            h.succeedTasks(); h.runs.getMine(newerB.getId());
            assertEquals(3, h.tasks.size()); assertEquals(15L, h.charged.get());
            req.setNodeId("edit"); if (!saved) req.setPreviousRunId(newerB.getId());
            var composed = h.runs.start(req);
            assertEquals("SUCCEEDED", composed.getStatus(), "saved=" + saved);
            assertEquals(3, h.tasks.size()); assertEquals(15L, h.charged.get());
            assertEquals(List.of("users/7/outputs/3/0.mp4", "users/7/outputs/1/0.mp4"), h.composedKeys);
            if (!saved) {
                h.stored.get(a.getId()).setUserId(8L);
                assertThrows(cn.aigc.dit.common.exception.BizException.class, () -> h.runs.start(req));
                assertEquals(3, h.tasks.size());
            }
        }
    }

    @Test
    void reopeningTargetedRunRestoresCompletedNodeBesideUnfinishedIndependentNode() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        req.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "a")),
                Harness.node("b", "VideoGenerator", Map.of())), "edges", List.of()));
        h.saved.setGraph(req.getGraph());
        var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
        JsonNode storedBefore = new ObjectMapper().findAndRegisterModules().valueToTree(h.stored);

        JsonNode snapshot = assertDoesNotThrow(() -> snapshot(h));

        assertEquals(List.of("users/7/outputs/1/0.mp4"), mediaKeys(snapshot));
        assertEquals("SUCCEEDED", snapshotStep(snapshot, "a").path("status").asText());
        assertEquals(a.getId().longValue(), snapshotStep(snapshot, "a").path("runId").asLong());
        assertIdleSnapshotStep(snapshot, "b");
        assertEquals(0, snapshot.path("activeRuns").size());
        assertEquals(1, h.tasks.size()); assertEquals(5L, h.charged.get());
        assertEquals(storedBefore, new ObjectMapper().findAndRegisterModules().valueToTree(h.stored));
    }

    @Test
    void invalidDependentBranchCannotHideCompletedSiblingOrActiveValidBranch() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
        req.setNodeId("b");
        var b = h.runs.start(req);
        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "a")),
                Harness.node("b", "VideoGenerator", Map.of("prompt", "b")),
                Harness.node("unfinished", "VideoGenerator", Map.of()),
                Harness.node("dependent", "VideoGenerator", Map.of("prompt", "dependent"))),
                "edges", List.of(Harness.edge("a", "unfinished"), Harness.edge("unfinished", "dependent"))));

        JsonNode snapshot = assertDoesNotThrow(() -> snapshot(h));

        assertEquals(List.of("users/7/outputs/1/0.mp4"), mediaKeys(snapshot));
        assertEquals("RUNNING", snapshotStep(snapshot, "b").path("status").asText());
        assertEquals(b.getId().longValue(), snapshot.path("activeRuns").get(0).path("id").asLong());
        assertIdleSnapshotStep(snapshot, "unfinished");
        assertIdleSnapshotStep(snapshot, "dependent");
        assertEquals(2, h.tasks.size()); assertEquals(10L, h.charged.get());
        h.succeedTasks(); h.runs.getMine(b.getId());
        assertEquals(List.of("users/7/outputs/1/0.mp4", "users/7/outputs/2/0.mp4"), mediaKeys(snapshot(h)));
    }

    @Test
    void invalidBranchesDoNotBypassHistoryOwnershipOrCurrentInputFingerprints() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
        req.setNodeId("b");
        var b = h.runs.start(req); h.succeedTasks(); h.runs.getMine(b.getId());
        WorkflowRun foreign = Harness.copy(h.stored.get(a.getId())); foreign.setId(900L); foreign.setUserId(8L);
        foreign.getSteps().get(0).put("outputs", List.of(Map.of("type", "video", "storageKey", "users/8/private.mp4")));
        h.stored.put(900L, foreign);
        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "a")),
                Harness.node("b", "VideoGenerator", Map.of("prompt", "changed")),
                Harness.node("unfinished", "VideoGenerator", Map.of())), "edges", List.of()));

        JsonNode snapshot = assertDoesNotThrow(() -> snapshot(h));

        assertEquals(List.of("users/7/outputs/1/0.mp4"), mediaKeys(snapshot));
        assertEquals(a.getId().longValue(), snapshotStep(snapshot, "a").path("runId").asLong());
        assertIdleSnapshotStep(snapshot, "b");
        assertIdleSnapshotStep(snapshot, "unfinished");
        h.stored.get(a.getId()).getSteps().get(0).put("outputs",
                List.of(Map.of("type", "video", "storageKey", "users/8/private.mp4")));
        assertIdleSnapshotStep(snapshot(h), "a");
        UserContext.set(new LoginUser(8L, null, null, null, LoginUser.ROLE_CREATOR));
        assertThrows(cn.aigc.dit.common.exception.BizException.class, () -> snapshot(h));
        assertEquals(2, h.tasks.size()); assertEquals(10L, h.charged.get());
    }

    @Test
    void malformedOrUnmodellableSavedBranchesCannotPoisonValidHistory() throws Exception {
        var aNode = Harness.node("a", "VideoGenerator", Map.of("prompt", "a"));
        var bNode = Harness.node("b", "VideoGenerator", Map.of("prompt", "b"));
        for (Map<String, Object> graph : List.of(
                Map.<String, Object>of("nodes", List.of(aNode, Harness.node("bad", "AudioGenerator",
                        Map.of("selectedAudioSource", "uploaded"))), "edges", List.of()),
                Map.<String, Object>of("nodes", List.of(aNode, Harness.node("bad", "AudioGenerator",
                        Map.of("prompt", "narration"))), "edges", List.of()),
                Map.<String, Object>of("nodes", List.of(aNode, bNode, Harness.node("bad", "VideoGenerator", Map.of("prompt", "bad"))),
                        "edges", List.of(Harness.edge("b", "bad"), Harness.edge("bad", "b"))),
                Map.<String, Object>of("nodes", List.of(aNode, bNode, Harness.node("bad", "VideoEditing", Map.of())),
                        "edges", List.of(Harness.edge("bad", "b"))))) {
            Harness h = new Harness();
            var req = h.independentRequest("a", true);
            var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
            h.saved.setGraph(graph);

            JsonNode snapshot = assertDoesNotThrow(() -> snapshot(h));

            assertEquals(List.of("users/7/outputs/1/0.mp4"), mediaKeys(snapshot));
            assertIdleSnapshotStep(snapshot, "bad");
            assertEquals(1, h.tasks.size()); assertEquals(5L, h.charged.get());
        }
    }

    @Test
    void newlyInvalidNodeCannotRestoreItsOldSuccessfulOutputs() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
        req.setNodeId("b");
        var b = h.runs.start(req); h.succeedTasks(); h.runs.getMine(b.getId());
        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "a")),
                Harness.node("b", "VideoGenerator", Map.of())), "edges", List.of()));

        JsonNode snapshot = assertDoesNotThrow(() -> snapshot(h));

        assertEquals(List.of("users/7/outputs/1/0.mp4"), mediaKeys(snapshot));
        assertIdleSnapshotStep(snapshot, "b");
        assertEquals(2, h.tasks.size()); assertEquals(10L, h.charged.get());
    }

    @Test
    void recoveryCannotRestoreAnEditingNodeMadeInvalidByANewOutgoingEdge() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("edit", true);
        var composed = h.runs.start(req); h.succeedTasks();
        assertEquals("SUCCEEDED", h.runs.getMine(composed.getId()).getStatus());
        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "a")),
                Harness.node("b", "VideoGenerator", Map.of("prompt", "b")),
                Harness.node("edit", "VideoEditing", Map.of()), Harness.node("downstream", "VideoGenerator", Map.of("prompt", "next"))),
                "edges", List.of(Harness.edge("b", "edit"), Harness.edge("a", "edit"), Harness.edge("edit", "downstream"))));

        JsonNode snapshot = snapshot(h);

        assertEquals(List.of("users/7/outputs/1/0.mp4", "users/7/outputs/2/0.mp4"), mediaKeys(snapshot));
        assertIdleSnapshotStep(snapshot, "edit");
        assertIdleSnapshotStep(snapshot, "downstream");
    }

    @Test
    void reopeningSavedGraphRestoresIndependentBranchesAndDropsChangedOrForeignOutputs() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
        req.setNodeId("b");
        var b = h.runs.start(req); h.succeedTasks(); h.runs.getMine(b.getId());
        WorkflowRun foreign = Harness.copy(h.stored.get(a.getId())); foreign.setId(900L); foreign.setUserId(8L);
        h.stored.put(900L, foreign);
        JsonNode snapshot = snapshot(h);
        assertEquals(List.of("users/7/outputs/1/0.mp4", "users/7/outputs/2/0.mp4"), mediaKeys(snapshot));
        assertEquals(0, snapshot.path("activeRuns").size());

        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("a", "VideoGenerator", Map.of("prompt", "changed")),
                Harness.node("b", "VideoGenerator", Map.of("prompt", "b")), Harness.node("edit", "VideoEditing", Map.of())),
                "edges", List.of(Harness.edge("a", "edit"), Harness.edge("b", "edit"))));
        snapshot = snapshot(h);
        assertEquals(List.of("users/7/outputs/2/0.mp4"), mediaKeys(snapshot));
        assertEquals("IDLE", snapshot.path("steps").get(0).path("status").asText());
        UserContext.set(new LoginUser(8L, null, null, null, LoginUser.ROLE_CREATOR));
        assertThrows(cn.aigc.dit.common.exception.BizException.class, () -> snapshot(h));
    }

    @Test
    void recoveryReportsEveryActiveRunAndKeepsNewestActiveStateBesideCompletedSibling() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var a = h.runs.start(req); h.succeedTasks(); h.runs.getMine(a.getId());
        req.setNodeId("b");
        var b = h.runs.start(req); h.succeedTasks(); h.runs.getMine(b.getId());
        var retryB = h.runs.start(req);
        JsonNode snapshot = snapshot(h);
        assertEquals(List.of("users/7/outputs/1/0.mp4"), mediaKeys(snapshot));
        assertEquals("RUNNING", snapshot.path("steps").get(1).path("status").asText());
        assertEquals(retryB.getId().longValue(), snapshot.path("activeRuns").get(0).path("id").asLong());
        assertEquals("RUNNING", snapshot.path("activeRuns").get(0).path("status").asText());
        req.setNodeId("a");
        var retryA = h.runs.start(req);
        assertEquals(2, snapshot(h).path("activeRuns").size());
        h.succeedTasks(); h.runs.getMine(retryA.getId()); h.runs.getMine(retryB.getId());
        assertEquals(List.of("users/7/outputs/4/0.mp4", "users/7/outputs/3/0.mp4"), mediaKeys(snapshot(h)));
    }

    @Test
    void queuedRecoveryMatchesTheEntireDependencyPlanAndDoesNotMutateStoredRuntime() throws Exception {
        Harness h = new Harness();
        var req = h.request("edit", "source", 0); h.saved.setGraph(req.getGraph());
        var started = h.runs.start(req);
        JsonNode snapshot = snapshot(h);
        assertEquals("RUNNING", snapshot.path("steps").get(0).path("status").asText());
        assertEquals("PENDING", snapshot.path("steps").get(1).path("status").asText());
        assertEquals(started.getId().longValue(), snapshot.path("steps").get(1).path("runId").asLong());
        var changed = h.request("edit", "changed source", 0); h.saved.setGraph(changed.getGraph());
        snapshot = snapshot(h);
        assertEquals("IDLE", snapshot.path("steps").get(0).path("status").asText());
        assertEquals("IDLE", snapshot.path("steps").get(1).path("status").asText());
        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("target", "VideoGenerator", Map.of("prompt", "target"))), "edges", List.of()));
        assertEquals("IDLE", assertDoesNotThrow(() -> snapshot(h)).path("steps").get(0).path("status").asText());
        assertEquals("RUNNING", h.stored.get(started.getId()).getSteps().get(0).get("status"));
        assertEquals("PENDING", h.stored.get(started.getId()).getSteps().get(1).get("status"));
        assertEquals(1, h.tasks.size());
    }

    @Test
    void snapshotRestoresGraphCarriedOutputsWhenOnlyUnrelatedNodesHaveRunHistory() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var run = h.runs.start(req); h.succeedTasks(); h.runs.getMine(run.getId());

        // 导入/模板工作流的产物只存在 graph 节点顶层 outputs，本地只跑过新加的配音节点。
        Map<String, Object> imported = new LinkedHashMap<>(Harness.node("imported", "VideoGenerator", Map.of("prompt", "imported")));
        imported.put("outputs", List.of(Map.of("type", "video", "storageKey", "users/7/imports/clip.mp4",
                "metadata", Map.of("durationSec", 5))));
        Map<String, Object> foreign = new LinkedHashMap<>(Harness.node("foreign", "VideoGenerator", Map.of("prompt", "foreign")));
        foreign.put("outputs", List.of(Map.of("type", "video", "storageKey", "users/8/private.mp4")));
        h.saved.setGraph(Map.of("nodes", List.of(
                Harness.node("a", "VideoGenerator", Map.of("prompt", "changed")), imported, foreign),
                "edges", List.of()));

        JsonNode snapshot = snapshot(h);
        assertEquals("SUCCEEDED", snapshotStep(snapshot, "imported").path("status").asText());
        assertEquals("users/7/imports/clip.mp4",
                snapshotStep(snapshot, "imported").path("outputs").get(0).path("storageKey").asText());
        // 参数变了的历史节点、以及指向别人私有对象的产物都不该被回显
        assertIdleSnapshotStep(snapshot, "a");
        assertIdleSnapshotStep(snapshot, "foreign");
    }

    @Test
    void snapshotNeverExposesUnexecutedOrForeignUploadedRuntimeOutputs() throws Exception {
        Harness h = new Harness();
        var req = h.independentRequest("a", true);
        var run = h.runs.start(req); h.succeedTasks(); h.runs.getMine(run.getId());
        h.saved.setGraph(Map.of("nodes", List.of(Harness.node("audio", "AudioGenerator", Map.of(
                "selectedAudioSource", "uploaded", "uploadedAudio", Map.of("storageKey", "users/8/private.wav")))), "edges", List.of()));
        assertEquals(List.of(), mediaKeys(snapshot(h)));
    }

    private static JsonNode snapshot(Harness h) throws Exception {
        return new ObjectMapper().findAndRegisterModules().valueToTree(h.runs.snapshotMine(99L));
    }

    private static JsonNode snapshotStep(JsonNode snapshot, String nodeId) {
        for (JsonNode step : snapshot.path("steps")) {
            if (nodeId.equals(step.path("nodeId").asText())) return step;
        }
        throw new AssertionError("Missing snapshot step: " + nodeId);
    }

    private static void assertIdleSnapshotStep(JsonNode snapshot, String nodeId) {
        JsonNode step = snapshotStep(snapshot, nodeId);
        assertEquals("IDLE", step.path("status").asText(), nodeId);
        assertEquals(0, step.path("outputs").size(), nodeId);
        for (String field : List.of("errorMessage", "taskId", "runId")) {
            assertFalse(step.hasNonNull(field), nodeId + ": " + field);
        }
    }

    private static List<String> mediaKeys(JsonNode snapshot) {
        List<String> keys = new ArrayList<>();
        snapshot.path("steps").forEach(step -> step.path("outputs").forEach(output -> {
            if (output.hasNonNull("storageKey")) keys.add(output.path("storageKey").asText());
        }));
        return keys;
    }

    /** 运行推进的「上游素材已落盘」预检用：单测里所有引用都视为已就绪。 */
    private static cn.aigc.dit.storage.ObjectStorage alwaysPresentStorage() {
        cn.aigc.dit.storage.ObjectStorage storage = mock(cn.aigc.dit.storage.ObjectStorage.class);
        lenient().when(storage.exists(anyString())).thenReturn(true);
        return storage;
    }

    private static final class Harness {
        final Map<Long, WorkflowRun> stored = new LinkedHashMap<>();
        final Map<Long, GenTask> tasks = new LinkedHashMap<>();
        final AtomicLong charged = new AtomicLong();
        final List<String> composedKeys = new ArrayList<>();
        final WorkflowRunService runs;
        final WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        final AtomicLong runIds = new AtomicLong();
        final Workflow saved = new Workflow();
        Harness() {
            UserContext.set(new LoginUser(7L, null, null, null, LoginUser.ROLE_CREATOR));
            AiModelMapper models = mock(AiModelMapper.class);
            AiModel model = new AiModel(); model.setId(2L); model.setName("Video"); model.setCategory("video");
            model.setEnabled(true); model.setAdapterKey("mock"); model.setModelKey("mock-video");
            model.setPricing(Map.of("unit", "次", "sale", Map.of("output", 5)));
            when(models.selectList(any())).thenReturn(List.of(model)); when(models.selectById(2L)).thenReturn(model);
            GenTaskMapper taskMapper = mock(GenTaskMapper.class);
            when(taskMapper.insert(any(GenTask.class))).thenAnswer(call -> {
                GenTask task = call.getArgument(0); task.setId((long) tasks.size() + 1); tasks.put(task.getId(), task); return 1;
            });
            when(taskMapper.selectById(anyLong())).thenAnswer(call -> tasks.get(call.getArgument(0)));
            when(taskMapper.selectList(any())).thenReturn(List.of());
            CreationService creations = mock(CreationService.class);
            when(creations.toTaskResp(any(), any(), any())).thenAnswer(call -> {
                GenTask task = call.getArgument(0); GenerateTaskResp resp = new GenerateTaskResp();
                resp.setId(task.getId()); resp.setCreditCost(task.getCreditCost()); resp.setProgress(0); return resp;
            });
            CreditService credits = mock(CreditService.class);
            doAnswer(call -> { charged.addAndGet(-((Number) call.getArgument(1)).longValue()); return null; })
                    .when(credits).change(anyLong(), anyLong(), anyString(), anyString(), anyString());
            GenerateService generate = new GenerateService(models, mock(CreationMapper.class), creations,
                    mock(AssetService.class), credits, mock(GenerateTaskPublisher.class), mock(ObjectProvider.class), Runnable::run);
            ReflectionTestUtils.setField(generate, "baseMapper", taskMapper);
            when(runMapper.insert(any(WorkflowRun.class))).thenAnswer(call -> {
                WorkflowRun run = call.getArgument(0); run.setId(runIds.incrementAndGet()); stored.put(run.getId(), copy(run)); return 1;
            });
            when(runMapper.updateById(any(WorkflowRun.class))).thenAnswer(call -> {
                WorkflowRun run = call.getArgument(0); stored.put(run.getId(), copy(run)); return 1;
            });
            when(runMapper.selectById(anyLong())).thenAnswer(call -> copy(stored.get(call.getArgument(0))));
            when(runMapper.selectList(any())).thenAnswer(call -> {
                QueryWrapper<WorkflowRun> query = call.getArgument(0);
                String sql = query.getSqlSegment();
                assertTrue(sql.contains("user_id =") && sql.contains("workflow_id =") && sql.contains("ORDER BY id DESC"), sql);
                assertTrue(query.getParamNameValuePairs().containsValue(7L));
                assertTrue(query.getParamNameValuePairs().containsValue(99L));
                var limit = java.util.regex.Pattern.compile("LIMIT (\\d+)").matcher(sql);
                assertTrue(limit.find(), "history queries must be bounded");
                return stored.values().stream().filter(run -> run.getUserId().equals(7L) && run.getWorkflowId().equals(99L))
                        .sorted(Comparator.comparing(WorkflowRun::getId).reversed()).limit(Long.parseLong(limit.group(1))).map(Harness::copy).toList();
            });
            WorkflowFilmComposer composer = mock(WorkflowFilmComposer.class);
            // 桩必须挂在**运行服务实际调用的 8 参重载**上（clips, target, audioClips, styles, overlays）。
            // Mockito mock 接口时 default 重载不会自动委托到低阶方法，挂 5 参会拿到 null 合成结果，
            // 表现为 compose 复用路径 PARTIAL_FAILED（14.36 改签名时此桩未跟进，14.44 补齐；
            // 14.55 新增第 9 参 pipClips 后再次需跟进——桩的参数个数必须与运行服务实际调用一致）。
            when(composer.compose(anyLong(), anyLong(), anyString(), anyList(), any(),
                    any(), any(), any(), any())).thenAnswer(call -> {
                List<WorkflowFilmComposer.Clip> clips = call.getArgument(3); composedKeys.clear();
                clips.forEach(clip -> composedKeys.add(clip.storageKey()));
                return new WorkflowFilmComposer.FilmOutput("users/7/workflows/film.mp4", null, 10);
            });
            WorkflowMapper workflows = mock(WorkflowMapper.class);
            saved.setId(99L); saved.setUserId(7L); saved.setModelId(2L); saved.setBuiltin(false);
            when(workflows.selectById(99L)).thenReturn(saved);
            runs = new WorkflowRunService(runMapper, taskMapper, models, generate, new WorkflowService(workflows, models), id -> () -> {}, composer, creations, alwaysPresentStorage());
        }
        WorkflowRunReq request(String target, String sourcePrompt, int selected) {
            WorkflowRunReq request = new WorkflowRunReq(); request.setWorkflowId(99L); request.setModelId(2L); request.setNodeId(target);
            request.setGraph(Map.of("nodes", List.of(
                    node("source", "VideoGenerator", Map.of("prompt", sourcePrompt, "copies", 2, "selectedOutputIndex", selected)),
                    node("target", "VideoGenerator", Map.of("prompt", "target")), node("edit", "VideoEditing", Map.of())),
                    "edges", List.of(edge("source", "target"), edge("target", "edit"))));
            return request;
        }
        WorkflowRunReq independentRequest(String target, boolean saved) {
            WorkflowRunReq request = new WorkflowRunReq(); request.setWorkflowId(saved ? 99L : null); request.setModelId(2L); request.setNodeId(target);
            request.setGraph(Map.of("nodes", List.of(node("a", "VideoGenerator", Map.of("prompt", "a")),
                    node("b", "VideoGenerator", Map.of("prompt", "b")), node("edit", "VideoEditing", Map.of())),
                    "edges", List.of(edge("b", "edit"), edge("a", "edit"))));
            this.saved.setGraph(request.getGraph());
            return request;
        }
        void succeedTasks() {
            tasks.values().forEach(task -> { task.setStatus("SUCCEEDED"); task.setProgress(100);
                task.setOutputKeys(java.util.stream.IntStream.range(0, task.getCopies()).mapToObj(index -> "users/7/outputs/" + task.getId() + "/" + index + ".mp4").toList());
                task.setOutputStorageKey(task.getOutputKeys().get(0)); });
        }
        static Map<String, Object> node(String id, String kind, Map<String, Object> media) {
            return Map.of("id", id, "data", Map.of("kind", kind, "media", media,
                    "runtime", Map.of("status", "SUCCEEDED", "outputs", List.of(Map.of("storageKey", "users/8/forged.mp4")))));
        }
        static Map<String, Object> edge(String source, String target) { return Map.of("source", source, "target", target); }
        static WorkflowRun copy(WorkflowRun original) {
            if (original == null) return null;
            WorkflowRun result = new WorkflowRun(); result.setId(original.getId()); result.setUserId(original.getUserId()); result.setWorkflowId(original.getWorkflowId());
            result.setPreviousRunId(original.getPreviousRunId());
            result.setStatus(original.getStatus()); result.setTotal(original.getTotal()); result.setProgress(original.getProgress());
            result.setFinishedAt(original.getFinishedAt()); result.setErrorMessage(original.getErrorMessage());
            result.setSteps(original.getSteps().stream().map(step -> (Map<String,Object>)new LinkedHashMap<>(step)).toList());
            return result;
        }
    }
}
