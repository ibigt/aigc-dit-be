package cn.aigc.dit.server.workflow;

import java.util.List;

public interface WorkflowFilmComposer {

    record Clip(String storageKey, double trimStart, double trimEnd, boolean muted, double volume) {
    }

    record FilmOutput(String storageKey, String posterKey, double durationSeconds) {
    }

    FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips);

    FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips);
}
