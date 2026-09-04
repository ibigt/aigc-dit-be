package cn.aigc.dit.generate;

import java.util.ArrayList;
import java.util.List;

/**
 * 提交给 Provider 的 Canonical 生成请求。
 * 创作端与 {@code gen_task.params} 永远存这些字段；出站厂商 JSON 由 Adapter 翻译（M3）。
 */
public class GenerateRequest {
    private Long userId;
    private Long taskId;
    private String modelKey;
    private String category;
    private String mode;
    private String prompt;
    private String ratio;
    private String resolution;
    private String quality;
    private Integer durationSec;
    private Integer copies = 1;
    private List<ReferenceAsset> references = new ArrayList<>();
    private FramePair frames;

    public int resolvedCopies() {
        int value = copies == null ? 1 : copies;
        return Math.min(4, Math.max(1, value));
    }

    public Long getUserId() {
        return userId;
    }

    public GenerateRequest setUserId(Long userId) {
        this.userId = userId;
        return this;
    }

    public Long getTaskId() {
        return taskId;
    }

    public GenerateRequest setTaskId(Long taskId) {
        this.taskId = taskId;
        return this;
    }

    public String getModelKey() {
        return modelKey;
    }

    public GenerateRequest setModelKey(String modelKey) {
        this.modelKey = modelKey;
        return this;
    }

    public String getCategory() {
        return category;
    }

    public GenerateRequest setCategory(String category) {
        this.category = category;
        return this;
    }

    public String getMode() {
        return mode;
    }

    public GenerateRequest setMode(String mode) {
        this.mode = mode;
        return this;
    }

    public String getPrompt() {
        return prompt;
    }

    public GenerateRequest setPrompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public String getRatio() {
        return ratio;
    }

    public GenerateRequest setRatio(String ratio) {
        this.ratio = ratio;
        return this;
    }

    public String getResolution() {
        return resolution;
    }

    public GenerateRequest setResolution(String resolution) {
        this.resolution = resolution;
        return this;
    }

    public String getQuality() {
        return quality;
    }

    public GenerateRequest setQuality(String quality) {
        this.quality = quality;
        return this;
    }

    public Integer getDurationSec() {
        return durationSec;
    }

    public GenerateRequest setDurationSec(Integer durationSec) {
        this.durationSec = durationSec;
        return this;
    }

    public Integer getCopies() {
        return copies;
    }

    public GenerateRequest setCopies(Integer copies) {
        this.copies = copies;
        return this;
    }

    public List<ReferenceAsset> getReferences() {
        return references;
    }

    public GenerateRequest setReferences(List<ReferenceAsset> references) {
        this.references = references == null ? new ArrayList<>() : references;
        return this;
    }

    public FramePair getFrames() {
        return frames;
    }

    public GenerateRequest setFrames(FramePair frames) {
        this.frames = frames;
        return this;
    }
}
