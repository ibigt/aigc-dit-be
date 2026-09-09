package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.model.req.AdminModelSaveReq;
import cn.aigc.dit.server.model.resp.AdminModelResp;
import cn.aigc.dit.server.model.resp.ModelPublicResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminModelService extends ServiceImpl<AiModelMapper, AiModel> {

    private final AesGcmEncryptor encryptor;

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
        removeById(id);
    }

    private void apply(AiModel model, AdminModelSaveReq req, String modelKey, boolean creating) {
        String category = normalizeCategory(req.getCategory());
        AdapterCatalog.Spec spec = resolveSpec(req, model, category, modelKey, creating);
        if (spec.category() != null && !spec.category().equalsIgnoreCase(category)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "适配协议与模型类型不匹配");
        }
        model.setName(req.getName().trim());
        model.setModelKey(modelKey);
        model.setAdapterKey(spec.key());
        model.setProvider(spec.providerLabel());
        if (StringUtils.hasText(req.getEndpoint())) {
            model.setEndpoint(trimToNull(req.getEndpoint()));
        } else if (creating || !StringUtils.hasText(model.getEndpoint())) {
            model.setEndpoint(spec.defaultEndpoint());
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
            model.setApiKeyEnc(encryptor.encrypt(req.getApiKey().trim()));
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
        return AdapterCatalog.adminVisible().stream().map(AdapterCatalog::toAdminView).toList();
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
        if (ModelCategory.TOOL.equalsIgnoreCase(category)) {
            return ModelCategory.TOOL;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "模型类型仅支持 image / video / tool");
    }

    static String defaultBadge(String category) {
        if (ModelCategory.VIDEO.equals(category)) {
            return "视频";
        }
        if (ModelCategory.TOOL.equals(category)) {
            return "工具";
        }
        return "图像";
    }

    static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
