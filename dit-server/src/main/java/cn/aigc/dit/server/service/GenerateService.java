package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.agnes.AgnesRequestMapper;
import cn.aigc.dit.generate.comfyui.ComfyUiGeometry;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GenerateService extends ServiceImpl<GenTaskMapper, GenTask> {

    private final AiModelMapper aiModelMapper;
    private final CreationMapper creationMapper;
    private final CreationService creationService;
    private final AssetService assetService;
    private final CreditService creditService;
    private final GenerateTaskPublisher publisher;

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
        if (!GenerateRequests.supportsMode(model.getCapabilities(), request.getCategory(), request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, GenerateRequests.unsupportedModeMessage(request.getMode()));
        }
        String adapterKey = AdapterCatalog.resolveKey(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, GenerateRequests.unsupportedModeMessage(request.getMode()));
        }
        if (GenerateMode.I2V.equalsIgnoreCase(GenerateMode.canonical(request.getMode())) && request.getFrames() != null) {
            if (request.getFrames().getFirstFrameKey() == null || request.getFrames().getLastFrameKey() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "首尾帧模式需要同时提供首帧与尾帧");
            }
        }
        if (ModelCategory.VIDEO.equals(request.getCategory()) && request.getDurationSec() == null) {
            request.setDurationSec(5);
        }
        if (AdapterCatalog.MINIMAX_H3.equals(adapterKey) && ModelCategory.VIDEO.equals(request.getCategory())) {
            request.setDurationSec(ComfyUiGeometry.durationSec(request.getDurationSec()));
        }
        applyRunningHubGuardrails(model, request);
        applyAgnesGuardrails(adapterKey, request);
        long cost = CreditCalculator.calculate(
                model.getPricing(),
                request.getCategory(),
                request.getResolution(),
                request.getQuality(),
                request.getDurationSec(),
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
                model.getName() + " · " + (ModelCategory.VIDEO.equals(request.getCategory()) ? "视频生成" : "图片生成"));
        afterCommit(() -> publisher.publish(task.getId()));
        return creationService.toTaskResp(task, List.of(), model.getName());
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
