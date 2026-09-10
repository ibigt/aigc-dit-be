package cn.aigc.dit.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交给 Provider 的 Canonical 生成请求。
 * 创作端与 {@code gen_task.params} 永远存这些字段；出站厂商 JSON 由 Adapter 翻译（M3）。
 */
public class GenerateRequest {
    private Long userId;
    private Long taskId;
    private String modelKey;
    /** 适配协议（翻译包），如 gpt-image-2 / seedance-2 / minimax-h3 / agnes-image-2.5。 */
    private String adapterKey;
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
    /** 模型记录上的 Base URL，由 Worker 填入，不进创作端合同。 */
    private String endpoint;
    /** 解密后的 API Key 或环境变量回退，由 Worker 填入，禁止入参/日志落明文。 */
    private String apiKey;
    private Map<String, Object> adapterConfig = new LinkedHashMap<>();

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

    public String getAdapterKey() {
        return adapterKey;
    }

    public GenerateRequest setAdapterKey(String adapterKey) {
        this.adapterKey = adapterKey;
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

    public String getEndpoint() {
        return endpoint;
    }

    public GenerateRequest setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    public String getApiKey() {
        return apiKey;
    }

    public GenerateRequest setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public Map<String, Object> getAdapterConfig() {
        return adapterConfig;
    }

    public GenerateRequest setAdapterConfig(Map<String, Object> adapterConfig) {
        this.adapterConfig = adapterConfig == null ? new LinkedHashMap<>() : adapterConfig;
        return this;
    }
}
