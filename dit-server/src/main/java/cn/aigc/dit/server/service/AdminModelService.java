package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.AudioReferenceCapabilities;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProviderRegistry;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.ark.ArkKeys;
import cn.aigc.dit.generate.ark.ArkUrls;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.credit.CreditCalculator;
import cn.aigc.dit.server.credit.PricingPolicy;
import cn.aigc.dit.server.credit.SupplierBillingRules;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.DramaDurations;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.text.OpenAiCompatChatClient;
import cn.aigc.dit.server.drama.text.TextAdapterCatalog;
import cn.aigc.dit.server.drama.text.TextChatGatewayImpl;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.entity.SpeechVoice;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.SpeechVoiceMapper;
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
    private final GenerateProviderRegistry providerRegistry;
    private final GenerateProperties generateProperties;
    private final DramaSeriesMapper seriesMapper;
    private final GenTaskMapper genTaskMapper;
    private final CreationMapper creationMapper;
    private final SpeechVoiceMapper speechVoiceMapper;

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
                .in(AiModel::getCategory, ModelCategory.IMAGE, ModelCategory.VIDEO, ModelCategory.AUDIO)
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
        String category = trimToNull(req == null ? null : req.getCategory());
        String adapterKey = trimToNull(req == null ? null : req.getAdapterKey());
        String provider = trimToNull(req == null ? null : req.getProvider());

        if (req != null && req.getId() != null) {
            AiModel stored = requireModel(req.getId());
            if (!StringUtils.hasText(category)) {
                category = stored.getCategory();
            }
            if (!StringUtils.hasText(provider)) {
                provider = stored.getProvider();
            }
            if (!StringUtils.hasText(adapterKey)) {
                adapterKey = stored.getAdapterKey();
            }
            if (!StringUtils.hasText(apiKey)) {
                apiKey = encryptor.decrypt(stored.getApiKeyEnc());
            }
            if (!StringUtils.hasText(endpoint)) {
                endpoint = stored.getEndpoint();
            }
            if (!StringUtils.hasText(modelKey)) {
                modelKey = ModelCategory.TEXT.equalsIgnoreCase(category)
                        ? TextChatGatewayImpl.chatModelOf(stored)
                        : stored.getModelKey();
            }
        }

        if (!StringUtils.hasText(category)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少模型类型，请选择图像/视频/语音/文本后再测试");
        }

        if (ModelCategory.TEXT.equalsIgnoreCase(category)) {
            chatClient.ping(endpoint, apiKey, modelKey);
            return AdminModelTestConnectionResp.success();
        }

        if (!StringUtils.hasText(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先选择适配协议");
        }
        GenerateRequest probe = new GenerateRequest()
                .setAdapterKey(adapterKey)
                .setCategory(category)
                .setProvider(provider)
                .setEndpoint(endpoint)
                .setApiKey(apiKey)
                .setModelKey(modelKey);
        providerRegistry.resolveByAdapter(adapterKey, provider, category, modelKey).ping(probe);
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
        model.setProvider(resolveOfficialProvider(req.getProvider(), spec.key()));
        if (StringUtils.hasText(req.getEndpoint())) {
            model.setEndpoint(trimToNull(req.getEndpoint()));
        } else if (creating || !StringUtils.hasText(model.getEndpoint())) {
            model.setEndpoint(defaultEndpoint(spec, model.getProvider()));
        }
        if (AdapterCatalog.isOfficialTokenPlan(model.getProvider())
                && (AdapterCatalog.isArk(spec.key()) || AdapterCatalog.isSeedream(spec.key()))) {
            model.setEndpoint(ArkUrls.v3Base(model.getEndpoint(), spec.defaultEndpoint(), model.getProvider()));
        }
        model.setCategory(category);
        model.setCapabilities(AdapterCatalog.constrainCapabilities(req.getCapabilities(), category, spec));
        if (req.getAdapterConfig() != null) {
            model.setAdapterConfig(req.getAdapterConfig().isEmpty() ? null : req.getAdapterConfig());
        } else if (creating) {
            model.setAdapterConfig(null);
        }
        PricingPolicy.validate(category, req.getPricing());
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
        adapterConfig.putIfAbsent("enableThinking", false);
        adapterConfig.put("chatModel", modelKey);
        model.setAdapterConfig(adapterConfig.isEmpty() ? null : adapterConfig);
        PricingPolicy.validate(ModelCategory.TEXT, req.getPricing());
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
        applyAudioCapabilities(model, resp::setSupportsAudioReference,
                resp::setMaxAudioReferences, resp::setSupportsAudioOnlyReference);
        resp.setAdapterConfig(model.getAdapterConfig());
        Map<String, Object> pricing = model.getPricing() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(model.getPricing());
        resp.setConcurrentLimit(asInt(pricing.remove("concurrentLimit")));
        Object instanceType = pricing.remove("instanceType");
        resp.setInstanceType(instanceType == null ? null : String.valueOf(instanceType));
        Object proxy = pricing.remove("proxy");
        resp.setProxy(proxy == null ? null : String.valueOf(proxy));
        applyCharacterRule(model, pricing);
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

    /**
     * 把生效的语音字符计数口径写进下发用的 pricing——口径来自内置规则表
     * {@link cn.aigc.dit.server.credit.SupplierBillingRules}，前端直接读取展示，无需自行推断。
     */
    private void applyCharacterRule(AiModel model, Map<String, Object> pricing) {
        if (!ModelCategory.AUDIO.equalsIgnoreCase(model.getCategory())) {
            return;
        }
        SupplierBillingRules.CharacterRule rule = CreditCalculator.characterRule(
                model.getPricing(), model.getAdapterKey(), model.getProvider(), model.getModelKey());
        pricing.put("cjkWeight", rule.weight());
        pricing.put("cjkWeightLabel", rule.label());
        pricing.put("cjkWeightSource", rule.source());
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
        applyAudioCapabilities(model, resp::setSupportsAudioReference,
                resp::setMaxAudioReferences, resp::setSupportsAudioOnlyReference);
        Map<String, Object> pricing = model.getPricing() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(model.getPricing());
        pricing.remove("concurrentLimit");
        pricing.remove("instanceType");
        pricing.remove("proxy");
        applyCharacterRule(model, pricing);
        resp.setPricing(pricing);
        if (ModelCategory.AUDIO.equalsIgnoreCase(model.getCategory())) {
            Map<String, Object> options = publicAudioOptions(model.getAdapterConfig());
            if (isMiniMaxSpeechModel(model)) {
                options.put("voices", miniMaxSystemVoices(options.get("voices")));
            }
            resp.setAudioOptions(options);
        }
        resp.setEnabled(model.getEnabled());
        resp.setDescription(model.getDescription());
        resp.setBadge(model.getBadge());
        resp.setLogo(model.getLogo());
        applyVideoCapabilities(model, resp);
        return resp;
    }

    /**
     * 视频模型的时长可选档位（需求 A4 统一口径）。
     * 后端是唯一权威来源：合同 5–15 逐秒，再与当前适配器真实区间求交。
     * 前端（快速创作 / 高级工作台 / 短剧分镜）统一消费这份数据，
     * 不再各自按品牌名硬编码——此前三处口径漂移（工作台给出 4–12、分镜给出 5–15）。
     *
     * 清晰度档位暂不走这里：前后端对 MiniMax 官方（768P/2K）与 Wan（4K）的口径尚未对齐，
     * 贸然下发会把工作台已支持的档位收窄，等口径统一后再并入。
     */
    private static void applyVideoCapabilities(AiModel model, ModelPublicResp resp) {
        if (!ModelCategory.VIDEO.equalsIgnoreCase(model.getCategory())) {
            return;
        }
        String adapter = AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        resp.setDurations(DramaDurations.supported(adapter, model.getAdapterKey(), model.getModelKey()));
    }

    private static void applyAudioCapabilities(
            AiModel model,
            java.util.function.Consumer<Boolean> supported,
            java.util.function.Consumer<Integer> maxReferences,
            java.util.function.Consumer<Boolean> audioOnly) {
        AudioReferenceCapabilities value = AudioReferenceCapabilities.resolve(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        supported.accept(value.supported());
        maxReferences.accept(value.maxReferences());
        audioOnly.accept(value.audioOnly());
    }

    private static Map<String, Object> publicAudioOptions(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        for (String key : List.of("voices", "formats", "defaultVoice", "defaultFormat", "minSpeed", "maxSpeed")) {
            if (config.get(key) != null) {
                result.put(key, config.get(key));
            }
        }
        return result;
    }

    /**
     * 兼容历史模型记录：早期管理端可能只保存了厂商或模型键，没有保存规范适配键。
     * 这类记录也必须使用完整的 MiniMax 系统音色目录，不能退回到少量默认音色。
     */
    private static boolean isMiniMaxSpeechModel(AiModel model) {
        if (AdapterCatalog.isMiniMaxSpeech(model.getAdapterKey())) {
            return true;
        }
        String provider = model.getProvider() == null ? "" : model.getProvider().toLowerCase();
        String modelKey = model.getModelKey() == null ? "" : model.getModelKey().toLowerCase();
        return (provider.contains("minimax") || provider.contains("稀宇")
                || modelKey.contains("minimax") || modelKey.contains("speech-"))
                && ModelCategory.AUDIO.equalsIgnoreCase(model.getCategory());
    }

    private List<Map<String, Object>> miniMaxSystemVoices(Object configuredVoices) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Boolean> included = new LinkedHashMap<>();
        speechVoiceMapper.selectList(new LambdaQueryWrapper<SpeechVoice>()
                        .eq(SpeechVoice::getProvider, "minimax")
                        .eq(SpeechVoice::getEnabled, true)
                        .orderByAsc(SpeechVoice::getSortOrder)
                        .orderByAsc(SpeechVoice::getId))
                .forEach(voice -> {
                    result.add(Map.of(
                            "id", voice.getVoiceId(),
                            "name", voice.getName(),
                            "language", voice.getLanguage()));
                    included.put(voice.getVoiceId(), true);
                });
        if (configuredVoices instanceof Iterable<?> values) {
            for (Object value : values) {
                Object rawId = value instanceof Map<?, ?> map ? map.get("id") : value;
                String id = rawId == null ? null : String.valueOf(rawId);
                if (StringUtils.hasText(id) && !included.containsKey(id)) {
                    result.add(Map.of("id", id, "name", id, "language", "自定义"));
                }
            }
        }
        return result;
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

    private static String defaultEndpoint(AdapterCatalog.Spec spec, String provider) {
        if (AdapterCatalog.isMiniMax(spec.key()) && AdapterCatalog.isComfyUiProvider(provider)) {
            return AdapterCatalog.MINIMAX_COMFY_ENDPOINT;
        }
        return spec.defaultEndpoint();
    }

    /**
     * Seedream/Ark 默认仍走 {@link AdapterCatalog#officialProviderOf}（5.0 固定 token plan）。
     * 仅 {@code dit.ark.allow-payg-provider=true} 时，显式「官方API」可落按量。
     */
    private String resolveOfficialProvider(String requested, String adapterKey) {
        String resolved = AdapterCatalog.officialProviderOf(requested, adapterKey);
        if (!generateProperties.getArk().isAllowPaygProvider()) {
            return resolved;
        }
        if (!(AdapterCatalog.isArk(adapterKey) || AdapterCatalog.isSeedream(adapterKey))) {
            return resolved;
        }
        if (!StringUtils.hasText(requested)) {
            return resolved;
        }
        if (requested.trim().equalsIgnoreCase(AdapterCatalog.PROVIDER_OFFICIAL)
                && !AdapterCatalog.isOfficialTokenPlan(requested)) {
            return AdapterCatalog.PROVIDER_OFFICIAL;
        }
        return resolved;
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
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            return ModelCategory.AUDIO;
        }
        if (ModelCategory.TOOL.equalsIgnoreCase(category)) {
            return ModelCategory.TOOL;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "模型类型仅支持 image / video / audio / text / tool");
    }

    static String defaultBadge(String category) {
        if (ModelCategory.VIDEO.equals(category)) {
            return "视频";
        }
        if (ModelCategory.TEXT.equals(category)) {
            return "文本";
        }
        if (ModelCategory.AUDIO.equals(category)) {
            return "语音";
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
