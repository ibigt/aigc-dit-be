package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.AudioReferenceCapabilities;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.ReferenceAsset;
import cn.aigc.dit.generate.agnes.AgnesRequestMapper;
import cn.aigc.dit.generate.ark.ArkRequestMapper;
import cn.aigc.dit.generate.comfyui.ComfyUiGeometry;
import cn.aigc.dit.generate.minimax.MiniMaxRequestMapper;
import cn.aigc.dit.generate.runninghub.RunningHubGenerateProvider;
import cn.aigc.dit.server.credit.CreditCalculator;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.generate.GenerateTaskPublisher;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.worker.GenerateWorker;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class GenerateService extends ServiceImpl<GenTaskMapper, GenTask> {

    private final AiModelMapper aiModelMapper;
    private final CreationMapper creationMapper;
    private final CreationService creationService;
    private final AssetService assetService;
    private final CreditService creditService;
    private final GenerateTaskPublisher publisher;
    private final ObjectProvider<GenerateWorker> generateWorker;
    @Qualifier("generateKickExecutor")
    private final Executor generateKickExecutor;

    @Transactional
    public GenerateTaskResp submit(GenerateSubmitReq req) {
        return submit(req, null);
    }

    @Transactional
    public GenerateTaskResp submit(GenerateSubmitReq req, Map<String, Object> extraParams) {
        Long userId = UserContext.requireUserId();
        AiModel model = requireEnabledModel(req.getModelId());
        if (!model.getCategory().equalsIgnoreCase(req.getCategory())
                && (model.getCapabilities() == null || model.getCapabilities().stream()
                .noneMatch(item -> item.equalsIgnoreCase(req.getCategory())))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型不支持当前创作类型");
        }
        GenerateRequest request = GenerateRequests.fromSubmit(userId, model.getModelKey(), req, assetService);
        String adapterKey = AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        request.setAdapterKey(adapterKey);
        validateAudioRequest(request);
        validatePromptAndAudio(model, request);
        if (!modeSupported(model.getCapabilities(), adapterKey, request.getCategory(), request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, GenerateRequests.unsupportedModeMessage(request.getMode()));
        }
        if (GenerateMode.I2V.equalsIgnoreCase(GenerateMode.canonical(request.getMode())) && request.getFrames() != null) {
            if (request.getFrames().getFirstFrameKey() == null || request.getFrames().getFirstFrameKey().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "首尾帧模式需要提供首帧");
            }
        }
        if (ModelCategory.VIDEO.equals(request.getCategory()) && request.getDurationSec() == null) {
            request.setDurationSec(5);
        }
        applyRunningHubGuardrails(model, request);
        applyAgnesGuardrails(adapterKey, request);
        applyArkGuardrails(adapterKey, request);
        applyMiniMaxGuardrails(adapterKey, model.getProvider(), request);
        long cost = CreditCalculator.calculate(
                model.getPricing(),
                request.getCategory(),
                request.getResolution(),
                request.getQuality(),
                billingQuantity(request),
                request.resolvedCopies());
        boolean bindSession = GenerateRequests.bindsQuickCreate(extraParams);
        CreationSession session = bindSession
                ? creationService.ensureSession(req.getSessionId(), userId, request.getCategory(), request.getPrompt())
                : null;
        GenTask task = new GenTask();
        task.setUserId(userId);
        task.setModelId(model.getId());
        task.setCategory(request.getCategory());
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setPrompt(request.getPrompt());
        Map<String, Object> params = GenerateRequests.toParams(request, session == null ? null : session.getId());
        if (extraParams != null) {
            extraParams.forEach((key, value) -> {
                if (value != null) {
                    params.put(key, value);
                }
            });
        }
        task.setParams(params);
        task.setCreditCost(cost);
        task.setCopies(request.resolvedCopies());
        save(task);
        request.setTaskId(task.getId());
        creditService.change(userId, -cost, CreditReason.GENERATE, String.valueOf(task.getId()),
                model.getName() + " · " + creationLabel(request.getCategory()));
        afterCommit(() -> {
            publisher.publish(task.getId());
            GenerateWorker worker = generateWorker.getIfAvailable();
            if (worker != null) {
                generateKickExecutor.execute(() -> worker.kick(task.getId()));
            }
        });
        return creationService.toTaskResp(task, List.of(), model.getName());
    }

    static Integer billingQuantity(GenerateRequest request) {
        if (ModelCategory.AUDIO.equalsIgnoreCase(request.getCategory())) {
            return request.getPrompt() == null ? 0 : request.getPrompt().length();
        }
        return request.getDurationSec();
    }

    private static void validatePromptAndAudio(AiModel model, GenerateRequest request) {
        List<ReferenceAsset> refs = request.getReferences() == null ? List.of() : request.getReferences();
        long audioCount = refs.stream()
                .filter(java.util.Objects::nonNull)
                .filter(ref -> AssetKind.AUDIO.equalsIgnoreCase(ref.getType()))
                .count();
        if (audioCount == 0) {
            if ((request.getPrompt() == null || request.getPrompt().isBlank())
                    && !GenerateMode.allowBlankPrompt(request.getFrames())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
            }
            return;
        }
        AudioReferenceCapabilities capability = AudioReferenceCapabilities.resolve(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        if (!capability.supported()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前视频模型不支持上传参考音频");
        }
        if (audioCount > capability.maxReferences()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "当前视频模型最多支持 " + capability.maxReferences() + " 段参考音频");
        }
        boolean hasVisualReference = refs.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(ref -> !AssetKind.AUDIO.equalsIgnoreCase(ref.getType()));
        if (!hasVisualReference && !capability.audioOnly()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前视频模型的参考音频必须搭配图片或视频");
        }
    }

    private static void validateAudioRequest(GenerateRequest request) {
        if (!ModelCategory.AUDIO.equalsIgnoreCase(request.getCategory())) {
            return;
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "待朗读文本不能为空");
        }
        if (request.getReferences() != null && !request.getReferences().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文本生成语音暂不支持参考素材");
        }
        if (request.getFrames() != null && request.getFrames().hasAny()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文本生成语音不支持首尾帧");
        }
        request.setCopies(1);
        if (request.getSpeed() == null) {
            request.setSpeed(1.0);
        }
        if (request.getAudioFormat() == null || request.getAudioFormat().isBlank()) {
            request.setAudioFormat("mp3");
        }
    }

    private static String creationLabel(String category) {
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return "视频生成";
        }
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            return "语音生成";
        }
        return "图片生成";
    }

    public GenerateTaskResp getMine(Long taskId) {
        GenTask task = requireOwned(taskId);
        AiModel model = task.getModelId() == null ? null : aiModelMapper.selectById(task.getModelId());
        List<Creation> creations = creationMapper.selectList(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getTaskId, taskId)
                .orderByAsc(Creation::getCopyIndex));
        return creationService.toTaskResp(task, creations, model == null ? null : model.getName());
    }

    public GenTask requireOwned(Long taskId) {
        GenTask task = getById(taskId);
        if (task == null || !task.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private static void applyAgnesGuardrails(String adapterKey, GenerateRequest request) {
        if (!AdapterCatalog.isAgnes(adapterKey)) {
            return;
        }
        request.setCopies(1);
        if (ModelCategory.VIDEO.equals(request.getCategory())) {
            request.setDurationSec(AgnesRequestMapper.clampSeconds(request.getDurationSec()));
        }
    }

    private static void applyArkGuardrails(String adapterKey, GenerateRequest request) {
        if (!AdapterCatalog.isArk(adapterKey)) {
            return;
        }
        request.setCopies(1);
        if (ModelCategory.VIDEO.equals(request.getCategory())) {
            request.setDurationSec(ArkRequestMapper.clampSeconds(request.getDurationSec()));
            request.setResolution(ArkRequestMapper.clampResolution(request));
        }
    }

    private static void applyMiniMaxGuardrails(String adapterKey, String provider, GenerateRequest request) {
        if (!AdapterCatalog.isMiniMax(adapterKey)) {
            return;
        }
        request.setCopies(1);
        if (!ModelCategory.VIDEO.equals(request.getCategory())) {
            return;
        }
        if (AdapterCatalog.isComfyUiProvider(provider)) {
            request.setDurationSec(ComfyUiGeometry.durationSec(request.getDurationSec()));
            return;
        }
        request.setDurationSec(MiniMaxRequestMapper.clampSeconds(request, request.getDurationSec()));
        request.setResolution(MiniMaxRequestMapper.clampResolution(request));
    }

    private static void applyRunningHubGuardrails(AiModel model, GenerateRequest request) {
        String adapterKey = AdapterCatalog.resolveKey(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        if (!AdapterCatalog.isRunningHub(adapterKey)
                && (model.getProvider() == null || !RunningHubGenerateProvider.KEY.equalsIgnoreCase(model.getProvider()))) {
            return;
        }
        request.setCopies(1);
        if (GenerateMode.T2I.equalsIgnoreCase(request.getMode())
                || GenerateMode.I2I.equalsIgnoreCase(request.getMode())) {
            request.setResolution("1K");
            request.setQuality("Low");
        }
    }

    /**
     * 已识别适配协议时以厂商协议为准（管理端 capabilities 漏写 i2v 时，官方 Seedance 仍可走首尾帧）。
     */
    static boolean modeSupported(List<String> capabilities, String adapterKey, String category, String mode) {
        if (adapterKey != null) {
            return AdapterCatalog.supportsMode(adapterKey, mode);
        }
        return GenerateRequests.supportsMode(capabilities, category, mode);
    }

    private AiModel requireEnabledModel(Long modelId) {
        AiModel model = aiModelMapper.selectById(modelId);
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型不可用");
        }
        if (!ModelCategory.isCreatable(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该模型不能用于快速创作");
        }
        return model;
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
