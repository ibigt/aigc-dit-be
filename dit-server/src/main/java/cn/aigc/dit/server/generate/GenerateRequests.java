package cn.aigc.dit.server.generate;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.FramePair;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.ReferenceAsset;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.service.AssetService;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical {@link GenerateRequest} 与任务 params 互转。
 */
public final class GenerateRequests {

    public static final String SOURCE = "快速创作";
    public static final String SOURCE_DRAMA = "漫剧";
    public static final int MAX_REFERENCES = 15;

    private GenerateRequests() {
    }

    public static GenerateRequest fromSubmit(Long userId, String modelKey, GenerateSubmitReq req, AssetService assetService) {
        String category = normalizeCategory(req.getCategory());
        List<ReferenceAsset> references = resolveReferences(req, assetService);
        FramePair frames = resolveFrames(req, assetService);
        String mode = GenerateMode.derive(category, references, frames);
        return new GenerateRequest()
                .setUserId(userId)
                .setModelKey(modelKey)
                .setCategory(category)
                .setMode(mode)
                .setPrompt(req.getPrompt() == null ? "" : req.getPrompt().trim())
                .setRatio(blankToNull(req.getRatio()))
                .setResolution(blankToNull(req.getResolution()))
                .setQuality(blankToNull(req.getQuality()))
                .setDurationSec(req.getDurationSec())
                .setReferenceVideoSeconds(req.getReferenceVideoSeconds())
                .setVoiceId(blankToNull(req.getVoiceId()))
                .setAudioMode(blankToNull(req.getAudioMode()))
                .setEmotion(blankToNull(req.getEmotion()))
                .setLyrics(blankToNull(req.getLyrics()))
                .setInstrumental(req.getInstrumental())
                .setSpeed(req.getSpeed())
                .setAudioFormat(blankToNull(req.getAudioFormat()))
                .setCopies(req.getCopies() == null ? 1 : req.getCopies())
                .setReferences(references)
                .setFrames(frames);
    }

    /** 漫剧任务不挂快速创作会话。图像和视频成功后仍写入「我的创作」。 */
    public static boolean isDramaSource(Map<String, Object> params) {
        return SOURCE_DRAMA.equals(sourceOf(params));
    }

    /** 分镜出片任务（视频成片；开场静帧另见 {@link #isOpeningStill}）。 */
    public static boolean isDramaShot(Map<String, Object> params) {
        return isDramaSource(params) && params != null && params.get("shotId") != null && !isOpeningStill(params);
    }

    /** 分镜开场静帧（i2i），随后可能衔接到 i2v。 */
    public static boolean isOpeningStill(Map<String, Object> params) {
        return isDramaSource(params) && params != null && params.get("shotId") != null && truthy(params.get("openingStill"));
    }

    public static boolean continueStillToI2v(Map<String, Object> params) {
        return isOpeningStill(params) && truthy(params.get("stillForI2v"));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    public static boolean bindsQuickCreate(Map<String, Object> params) {
        return !isDramaSource(params);
    }

    public static String sourceOf(Map<String, Object> params) {
        if (params == null || params.get("source") == null) {
            return SOURCE;
        }
        String text = String.valueOf(params.get("source")).trim();
        return text.isEmpty() || "null".equals(text) ? SOURCE : text;
    }

    public static Long sessionIdFromParams(Map<String, Object> params) {
        return longVal(params, "sessionId");
    }

    public static Long shotIdFromParams(Map<String, Object> params) {
        return longVal(params, "shotId");
    }

    public static Long seriesIdFromParams(Map<String, Object> params) {
        return longVal(params, "seriesId");
    }

    private static Long longVal(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) {
            return null;
        }
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Map<String, Object> toParams(GenerateRequest request, Long sessionId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", SOURCE);
        params.put("mode", request.getMode());
        params.put("ratio", request.getRatio());
        params.put("resolution", request.getResolution());
        params.put("quality", request.getQuality());
        params.put("durationSec", request.getDurationSec());
        if (request.getReferenceVideoSeconds() != null) {
            params.put("referenceVideoSeconds", request.getReferenceVideoSeconds());
        }
        params.put("voiceId", request.getVoiceId());
        params.put("audioMode", request.getAudioMode());
        params.put("emotion", request.getEmotion());
        params.put("lyrics", request.getLyrics());
        params.put("instrumental", request.getInstrumental());
        params.put("speed", request.getSpeed());
        params.put("audioFormat", request.getAudioFormat());
        params.put("copies", request.resolvedCopies());
        if (request.getAdapterKey() != null && !request.getAdapterKey().isBlank()) {
            params.put("adapterKey", request.getAdapterKey());
        }
        if (sessionId != null) {
            params.put("sessionId", sessionId);
        }
        if (request.getReferences() != null && !request.getReferences().isEmpty()) {
            List<Map<String, String>> refs = new ArrayList<>();
            for (ReferenceAsset ref : request.getReferences()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("type", ref.getType());
                item.put("storageKey", ref.getStorageKey());
                refs.add(item);
            }
            params.put("references", refs);
        }
        if (request.getFrames() != null && request.getFrames().hasAny()) {
            Map<String, String> frames = new LinkedHashMap<>();
            frames.put("firstFrameKey", request.getFrames().getFirstFrameKey());
            frames.put("lastFrameKey", request.getFrames().getLastFrameKey());
            params.put("frames", frames);
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    public static GenerateRequest fromTask(GenTask task, String modelKey) {
        Map<String, Object> params = task.getParams() == null ? Map.of() : task.getParams();
        List<ReferenceAsset> references = new ArrayList<>();
        Object rawRefs = params.get("references");
        if (rawRefs instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    references.add(new ReferenceAsset(
                            stringVal(map.get("type"), AssetKind.IMAGE),
                            stringVal(map.get("storageKey"), null)));
                }
            }
        }
        FramePair frames = null;
        Object rawFrames = params.get("frames");
        if (rawFrames instanceof Map<?, ?> map) {
            frames = new FramePair(stringVal(map.get("firstFrameKey"), null), stringVal(map.get("lastFrameKey"), null));
        }
        String category = task.getCategory();
        String mode = GenerateMode.canonical(stringVal(params.get("mode"), GenerateMode.derive(category, references, frames)));
        return new GenerateRequest()
                .setUserId(task.getUserId())
                .setTaskId(task.getId())
                .setModelKey(modelKey)
                .setAdapterKey(stringVal(params.get("adapterKey"), null))
                .setCategory(category)
                .setMode(mode)
                .setPrompt(task.getPrompt())
                .setRatio(stringVal(params.get("ratio"), null))
                .setResolution(stringVal(params.get("resolution"), null))
                .setQuality(stringVal(params.get("quality"), null))
                .setDurationSec(intVal(params.get("durationSec")))
                .setReferenceVideoSeconds(intVal(params.get("referenceVideoSeconds")))
                .setVoiceId(stringVal(params.get("voiceId"), null))
                .setAudioMode(stringVal(params.get("audioMode"), null))
                .setEmotion(stringVal(params.get("emotion"), null))
                .setLyrics(stringVal(params.get("lyrics"), null))
                .setInstrumental(params.get("instrumental") instanceof Boolean flag ? flag : null)
                .setSpeed(doubleVal(params.get("speed")))
                .setAudioFormat(stringVal(params.get("audioFormat"), null))
                .setCopies(task.getCopies() == null ? intVal(params.get("copies")) : task.getCopies())
                .setReferences(references)
                .setFrames(frames);
    }

    public static boolean supportsMode(List<String> capabilities, String category, String mode) {
        if (capabilities == null || capabilities.isEmpty()) {
            return true;
        }
        boolean hasSpecificMode = false;
        boolean modeAllowed = false;
        boolean categoryAllowed = false;
        for (String item : capabilities) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String cap = GenerateMode.canonical(item.trim());
            String wanted = GenerateMode.canonical(mode);
            if (GenerateMode.isSpecificMode(cap)) {
                hasSpecificMode = true;
                if (wanted != null && wanted.equalsIgnoreCase(cap)) {
                    modeAllowed = true;
                }
            }
            if ((wanted != null && wanted.equalsIgnoreCase(cap))
                    || (category != null && category.equalsIgnoreCase(item.trim()))) {
                categoryAllowed = true;
            }
        }
        if (hasSpecificMode) {
            return modeAllowed;
        }
        return categoryAllowed;
    }

    public static String unsupportedModeMessage(String mode) {
        String canonical = GenerateMode.canonical(mode);
        if (GenerateMode.I2I.equalsIgnoreCase(canonical)) {
            return "当前模型不支持参考图，请移除参考素材后再创作";
        }
        if (GenerateMode.R2V.equalsIgnoreCase(canonical)) {
            return "当前模型不支持参考生视频，请移除参考素材后再创作";
        }
        if (GenerateMode.I2V.equalsIgnoreCase(canonical)) {
            return "当前模型不支持首尾帧，请移除首尾帧后再创作";
        }
        if (GenerateMode.T2A.equalsIgnoreCase(canonical)) {
            return "当前模型不支持文本生成语音";
        }
        return "该模型不支持当前生成模式";
    }

    private static List<ReferenceAsset> resolveReferences(GenerateSubmitReq req, AssetService assetService) {
        if (req.getReferences() == null || req.getReferences().isEmpty()) {
            return List.of();
        }
        if (req.getReferences().size() > MAX_REFERENCES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材最多选择15个");
        }
        List<ReferenceAsset> result = new ArrayList<>();
        for (GenerateSubmitReq.ReferenceInput input : req.getReferences()) {
            String key = input.getStorageKey();
            String type = input.getType();
            Long durationMs = null;
            if (input.getAssetId() != null) {
                Asset asset = assetService.requireOwned(input.getAssetId());
                key = asset.getStorageKey();
                type = asset.getKind();
                durationMs = asset.getDurationMs();
            } else if (StringUtils.hasText(key)) {
                assetService.assertOwnedStorageKey(key);
                type = StringUtils.hasText(type) ? type : AssetKind.IMAGE;
                durationMs = assetService.findOwnedDurationMs(key);
            } else {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考素材缺少 storageKey");
            }
            String normalized = AssetKind.VIDEO.equalsIgnoreCase(type) ? AssetKind.VIDEO
                    : AssetKind.AUDIO.equalsIgnoreCase(type) ? AssetKind.AUDIO : AssetKind.IMAGE;
            result.add(new ReferenceAsset(normalized, key, durationMs));
        }
        return result;
    }

    private static FramePair resolveFrames(GenerateSubmitReq req, AssetService assetService) {
        if (req.getFrames() == null) {
            return null;
        }
        String first = blankToNull(req.getFrames().getFirstFrameKey());
        String last = blankToNull(req.getFrames().getLastFrameKey());
        if (first != null) {
            assetService.assertOwnedStorageKey(first);
        }
        if (last != null) {
            assetService.assertOwnedStorageKey(last);
        }
        FramePair frames = new FramePair(first, last);
        return frames.hasAny() ? frames : null;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String stringVal(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equals(text) ? fallback : text;
    }

    private static Integer intVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeCategory(String category) {
        if (ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return ModelCategory.IMAGE;
        }
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return ModelCategory.VIDEO;
        }
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            return ModelCategory.AUDIO;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "创作类型仅支持 image / video / audio");
    }
}
