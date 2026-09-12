package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.ark.ArkKeys;
import cn.aigc.dit.generate.ark.ArkUrls;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.text.OpenAiCompatChatClient;
import cn.aigc.dit.server.drama.text.TextAdapterCatalog;
import cn.aigc.dit.server.drama.text.TextChatGatewayImpl;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.AdminModelSaveReq;
import cn.aigc.dit.server.model.req.AdminModelTestConnectionReq;
import cn.aigc.dit.server.model.resp.AdminModelResp;
import cn.aigc.dit.server.model.resp.AdminModelTestConnectionResp;
import cn.aigc.dit.server.model.resp.ModelPublicResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminModelService extends ServiceImpl<AiModelMapper, AiModel> {

    private final AesGcmEncryptor encryptor;
    private final OpenAiCompatChatClient chatClient;
    private final DramaSeriesMapper seriesMapper;
    private final GenTaskMapper genTaskMapper;
    private final CreationMapper creationMapper;

    public List<AdminModelResp> listAdmin(String keyword, String category, String provider) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(AiModel::getName, keyword)
                        .or().like(AiModel::getModelKey, keyword)
                        .or().like(AiModel::getDescription, keyword))
                .eq(StringUtils.hasText(category) && !"all".equalsIgnoreCase(category), AiModel::getCategory, category)
                .eq(StringUtils.hasText(provider), AiModel::getProvider, provider)
                .orderByDesc(AiModel::getCreatedAt);
        return list(wrapper).stream().map(this::toAdminResp).toList();
    }

    public List<ModelPublicResp> listEnabledPublic() {
        return list(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getEnabled, true)
                .in(AiModel::getCategory, ModelCategory.IMAGE, ModelCategory.VIDEO)
                .orderByAsc(AiModel::getId))
                .stream()
                .map(this::toPublicResp)
                .toList();
    }

    @Transactional
    public AdminModelResp create(AdminModelSaveReq req) {
        String modelKey = normalizeModelKey(req.getModelKey());
        assertModelKeyUnique(modelKey, null);
        AiModel model = new AiModel();
        apply(model, req, modelKey, true);
        save(model);
        return toAdminResp(model);
    }

    @Transactional
    public AdminModelResp update(Long id, AdminModelSaveReq req) {
        AiModel model = requireModel(id);
        String modelKey = normalizeModelKey(req.getModelKey());
        assertModelKeyUnique(modelKey, id);
        apply(model, req, modelKey, false);
        updateById(model);
        return toAdminResp(model);
    }

    @Transactional
    public AdminModelResp changeEnabled(Long id, boolean enabled) {
        AiModel model = requireModel(id);
        model.setEnabled(enabled);
        updateById(model);
        return toAdminResp(model);
    }

    @Transactional
    public void delete(Long id) {
        requireModel(id);
        unbindReferences(id);
        if (!removeById(id)) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在");
        }
    }

    /**
     * {@code drama_series}/{@code gen_task}/{@code creation} 都外键指向 {@code ai_model}，
     * 不先置空的话删除会被数据库拦住，管理端表现为点击删除无效果。
     */
    private void unbindReferences(Long modelId) {
        seriesMapper.update(null, new UpdateWrapper<DramaSeries>()
                .eq("text_model_id", modelId)
                .set("text_model_id", null));
        seriesMapper.update(null, new UpdateWrapper<DramaSeries>()
                .eq("image_model_id", modelId)
                .set("image_model_id", null));
        seriesMapper.update(null, new UpdateWrapper<DramaSeries>()
                .eq("video_model_id", modelId)
                .set("video_model_id", null));
        genTaskMapper.update(null, new UpdateWrapper<GenTask>()
                .eq("model_id", modelId)
                .set("model_id", null));
        creationMapper.update(null, new UpdateWrapper<Creation>()
                .eq("model_id", modelId)
                .set("model_id", null));
    }

    public AdminModelTestConnectionResp testConnection(AdminModelTestConnectionReq req) {
        String endpoint = trimToNull(req == null ? null : req.getEndpoint());
        String modelKey = trimToNull(req == null ? null : req.getModelKey());
        String apiKey = trimToNull(req == null ? null : req.getApiKey());
        if (req != null && req.getId() != null) {
            AiModel stored = requireModel(req.getId());
            if (!ModelCategory.TEXT.equalsIgnoreCase(stored.getCategory())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "仅文本模型支持测试连接");
            }
            if (!StringUtils.hasText(apiKey)) {
                apiKey = encryptor.decrypt(stored.getApiKeyEnc());
            }
            if (!StringUtils.hasText(endpoint)) {
                endpoint = stored.getEndpoint();
            }
            if (!StringUtils.hasText(modelKey)) {
                modelKey = TextChatGatewayImpl.chatModelOf(stored);
            }
        }
        chatClient.ping(endpoint, apiKey, modelKey);
        return AdminModelTestConnectionResp.success();
    }

    private void apply(AiModel model, AdminModelSaveReq req, String modelKey, boolean creating) {
        String category = normalizeCategory(req.getCategory());
        if (ModelCategory.TEXT.equals(category)) {
            applyText(model, req, modelKey, creating);
            return;
        }
        AdapterCatalog.Spec spec = resolveSpec(req, model, category, modelKey, creating);
        if (spec.category() != null && !spec.category().equalsIgnoreCase(category)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "适配协议与模型类型不匹配");
        }
        model.setName(req.getName().trim());
        model.setModelKey(modelKey);
        model.setAdapterKey(spec.key());
        model.setProvider(AdapterCatalog.officialProviderOf(req.getProvider(), spec.key()));
        if (StringUtils.hasText(req.getEndpoint())) {
            model.setEndpoint(trimToNull(req.getEndpoint()));
        } else if (creating || !StringUtils.hasText(model.getEndpoint())) {
            model.setEndpoint(spec.defaultEndpoint());
        }
        if (AdapterCatalog.isOfficialTokenPlan(model.getProvider()) && AdapterCatalog.isArk(spec.key())) {
            model.setEndpoint(ArkUrls.v3Base(model.getEndpoint(), spec.defaultEndpoint(), model.getProvider()));
        }
        model.setCategory(category);
        model.setCapabilities(AdapterCatalog.constrainCapabilities(req.getCapabilities(), category, spec));
        if (req.getAdapterConfig() != null) {
            model.setAdapterConfig(req.getAdapterConfig().isEmpty() ? null : req.getAdapterConfig());
        } else if (creating) {
            model.setAdapterConfig(null);
        }
        model.setPricing(mergePricing(req));
        model.setEnabled(req.getEnabled() == null || req.getEnabled());
        model.setDescription(trimToNull(req.getDescription()));
        model.setBadge(StringUtils.hasText(req.getBadge()) ? req.getBadge().trim() : defaultBadge(category));
        model.setLogo(trimToNull(req.getLogo()));
        if (StringUtils.hasText(req.getApiKey())) {
            model.setApiKeyEnc(encryptor.encrypt(normalizeSecret(req.getApiKey())));
        } else if (creating) {
            model.setApiKeyEnc(null);
        }
    }

    private void applyText(AiModel model, AdminModelSaveReq req, String modelKey, boolean creating) {
        TextAdapterCatalog.Spec spec = resolveTextSpec(req, model, creating);
        model.setName(req.getName().trim());
        model.setModelKey(modelKey);
        model.setAdapterKey(spec.key());
        model.setProvider(spec.providerLabel());
        if (StringUtils.hasText(req.getEndpoint())) {
            model.setEndpoint(trimToNull(req.getEndpoint()));
        } else if (creating || !StringUtils.hasText(model.getEndpoint())) {
            model.setEndpoint(trimToNull(spec.defaultEndpoint()));
        }
        if (!StringUtils.hasText(model.getEndpoint())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写 Base URL");
        }
        model.setCategory(ModelCategory.TEXT);
        model.setCapabilities(List.of(ModelCategory.TEXT));
        Map<String, Object> adapterConfig = req.getAdapterConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(req.getAdapterConfig());
        adapterConfig.put("chatModel", modelKey);
        model.setAdapterConfig(adapterConfig.isEmpty() ? null : adapterConfig);
        model.setPricing(mergePricing(req));
        model.setEnabled(req.getEnabled() == null || req.getEnabled());
        model.setDescription(trimToNull(req.getDescription()));
        model.setBadge(StringUtils.hasText(req.getBadge()) ? req.getBadge().trim() : defaultBadge(ModelCategory.TEXT));
        model.setLogo(trimToNull(req.getLogo()));
        if (StringUtils.hasText(req.getApiKey())) {
            model.setApiKeyEnc(encryptor.encrypt(normalizeSecret(req.getApiKey())));
        } else if (creating) {
            model.setApiKeyEnc(null);
        }
    }

    private AiModel requireModel(Long id) {
        AiModel model = getById(id);
        if (model == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在");
        }
        return model;
    }

    private void assertModelKeyUnique(String modelKey, Long excludeId) {
        long exists = count(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getModelKey, modelKey)
                .ne(excludeId != null, AiModel::getId, excludeId));
        if (exists > 0) {
            throw new BizException(ErrorCode.CONFLICT, "模型标识已存在");
        }
    }

    AdminModelResp toAdminResp(AiModel model) {
        AdminModelResp resp = new AdminModelResp();
        resp.setId(model.getId());
        resp.setName(model.getName());
        resp.setModelKey(model.getModelKey());
        resp.setAdapterKey(model.getAdapterKey());
        resp.setProvider(model.getProvider());
        resp.setEndpoint(model.getEndpoint());
        resp.setCategory(model.getCategory());
        resp.setCapabilities(GenerateMode.canonicalizeCapabilities(model.getCapabilities()));
        resp.setAdapterConfig(model.getAdapterConfig());
        Map<String, Object> pricing = model.getPricing() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(model.getPricing());
        resp.setConcurrentLimit(asInt(pricing.remove("concurrentLimit")));
        Object instanceType = pricing.remove("instanceType");
        resp.setInstanceType(instanceType == null ? null : String.valueOf(instanceType));
        Object proxy = pricing.remove("proxy");
        resp.setProxy(proxy == null ? null : String.valueOf(proxy));
        resp.setPricing(pricing);
        resp.setEnabled(model.getEnabled());
        resp.setDescription(model.getDescription());
        resp.setBadge(model.getBadge());
        resp.setLogo(model.getLogo());
        resp.setHasApiKey(StringUtils.hasText(model.getApiKeyEnc()));
        resp.setCreatedAt(model.getCreatedAt());
        resp.setUpdatedAt(model.getUpdatedAt());
        return resp;
    }

    ModelPublicResp toPublicResp(AiModel model) {
        ModelPublicResp resp = new ModelPublicResp();
        resp.setId(model.getId());
        resp.setName(model.getName());
        resp.setModelKey(model.getModelKey());
        resp.setAdapterKey(model.getAdapterKey());
        resp.setProvider(model.getProvider());
        resp.setCategory(model.getCategory());
        resp.setCapabilities(GenerateMode.canonicalizeCapabilities(model.getCapabilities()));
        Map<String, Object> pricing = model.getPricing() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(model.getPricing());
        pricing.remove("concurrentLimit");
        pricing.remove("instanceType");
        pricing.remove("proxy");
        resp.setPricing(pricing);
        resp.setEnabled(model.getEnabled());
        resp.setDescription(model.getDescription());
        resp.setBadge(model.getBadge());
        resp.setLogo(model.getLogo());
        return resp;
    }

    static Map<String, Object> mergePricing(AdminModelSaveReq req) {
        Map<String, Object> pricing = req.getPricing() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(req.getPricing());
        if (req.getConcurrentLimit() != null) {
            pricing.put("concurrentLimit", req.getConcurrentLimit());
        }
        if (StringUtils.hasText(req.getInstanceType())) {
            pricing.put("instanceType", req.getInstanceType().trim());
        }
        if (StringUtils.hasText(req.getProxy())) {
            pricing.put("proxy", req.getProxy().trim());
        }
        return pricing;
    }

    public List<Map<String, Object>> listAdapters() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdapterCatalog.Spec spec : AdapterCatalog.adminVisible()) {
            rows.add(AdapterCatalog.toAdminView(spec));
        }
        for (TextAdapterCatalog.Spec spec : TextAdapterCatalog.adminVisible()) {
            rows.add(TextAdapterCatalog.toAdminView(spec));
        }
        return rows;
    }

    private AdapterCatalog.Spec resolveSpec(AdminModelSaveReq req, AiModel model, String category, String modelKey,
                                            boolean creating) {
        String requested = req.getAdapterKey();
        if (!StringUtils.hasText(requested) && !creating) {
            requested = model.getAdapterKey();
        }
        String resolved = AdapterCatalog.resolveKey(requested, req.getProvider(), category, modelKey);
        if (resolved == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择已接入的适配协议");
        }
        return AdapterCatalog.require(resolved);
    }

    private TextAdapterCatalog.Spec resolveTextSpec(AdminModelSaveReq req, AiModel model, boolean creating) {
        String requested = req.getAdapterKey();
        if (!StringUtils.hasText(requested) && !creating) {
            requested = model.getAdapterKey();
        }
        String resolved = TextAdapterCatalog.resolveKey(requested, req.getProvider(), req.getModelKey());
        if (resolved == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择已接入的文本适配协议（官方API 或自定义配置）");
        }
        return TextAdapterCatalog.require(resolved);
    }

    static List<String> normalizeCapabilities(List<String> capabilities, String category) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of(category);
        }
        List<String> values = GenerateMode.canonicalizeCapabilities(capabilities);
        return values == null || values.isEmpty() ? List.of(category) : values;
    }

    static String normalizeModelKey(String modelKey) {
        if (!StringUtils.hasText(modelKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型标识不能为空");
        }
        return modelKey.trim();
    }

    static String normalizeCategory(String category) {
        if (ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return ModelCategory.IMAGE;
        }
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return ModelCategory.VIDEO;
        }
        if (ModelCategory.TEXT.equalsIgnoreCase(category)) {
            return ModelCategory.TEXT;
        }
        if (ModelCategory.TOOL.equalsIgnoreCase(category)) {
            return ModelCategory.TOOL;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "模型类型仅支持 image / video / text / tool");
    }

    static String defaultBadge(String category) {
        if (ModelCategory.VIDEO.equals(category)) {
            return "视频";
        }
        if (ModelCategory.TEXT.equals(category)) {
            return "文本";
        }
        if (ModelCategory.TOOL.equals(category)) {
            return "工具";
        }
        return "图像";
    }

    static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    static String normalizeSecret(String value) {
        return ArkKeys.normalize(value);
    }

    static Integer asInt(Object value) {
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
}
