package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ark.ArkAssetApi;
import cn.aigc.dit.generate.ark.ArkAssetClient;
import cn.aigc.dit.generate.ark.ArkAssetCredentialSource;
import cn.aigc.dit.generate.ark.ArkAssetGroups;
import cn.aigc.dit.generate.ark.ArkAssetRefs;
import cn.aigc.dit.generate.ark.ArkAssetView;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.model.resp.DramaAssetReviewResp;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * 角色图点「角色审核」后入库。素材组整个环境只建一次；场景和道具不走这里。
 * 失败把方舟错误原文抛出去，不改图、不换通道再送。
 */
@Slf4j
@Service
public class ArkCharacterReview {

    static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    static final Duration POLL_DEADLINE = Duration.ofSeconds(120);
    static final Duration PRESIGN_TTL = Duration.ofMinutes(20);
    static final String NO_PUBLIC_URL = "当前存储签不出公网图片地址，不能入库";
    static final String TIMEOUT_MESSAGE = "角色审核超时，请再点一次";

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Ark ark;
    private final ArkAssetApi api;
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;
    private final Pause pause;
    private final Duration pollInterval;
    private final Duration deadline;
    private final ArkAssetCredentialSource credentials;
    private final Object groupLock = new Object();

    @Autowired
    public ArkCharacterReview(ObjectStorage objectStorage, GenerateProperties properties,
                              ArkAssetApi api, ObjectMapper objectMapper,
                              ObjectProvider<ArkAssetCredentialSource> credentials) {
        this(objectStorage, properties, api, objectMapper, System::currentTimeMillis,
                ArkCharacterReview::sleep, POLL_INTERVAL, POLL_DEADLINE, credentials.getIfAvailable());
    }

    ArkCharacterReview(ObjectStorage objectStorage, GenerateProperties properties, ArkAssetApi api,
                       ObjectMapper objectMapper, LongSupplier clock, Pause pause,
                       Duration pollInterval, Duration deadline) {
        this(objectStorage, properties, api, objectMapper, clock, pause, pollInterval, deadline, null);
    }

    ArkCharacterReview(ObjectStorage objectStorage, GenerateProperties properties, ArkAssetApi api,
                       ObjectMapper objectMapper, LongSupplier clock, Pause pause,
                       Duration pollInterval, Duration deadline, ArkAssetCredentialSource credentials) {
        this.objectStorage = objectStorage;
        this.ark = properties.getArk();
        this.api = api;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.pause = pause;
        this.pollInterval = pollInterval;
        this.deadline = deadline;
        this.credentials = credentials;
    }

    public DramaAssetReviewResp review(DramaAsset asset) {
        if (asset == null || !DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有角色资产可以审核");
        }
        if (!StringUtils.hasText(asset.getImageStorageKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先选择一张角色图");
        }
        String storageKey = asset.getImageStorageKey().trim();
        ArkAssetRefs.Ref existing = ArkAssetRefs.load(objectStorage, objectMapper, storageKey);
        if (existing != null && existing.active() && StringUtils.hasText(existing.assetId())) {
            return active(storageKey, existing.assetId());
        }
        assertCredentials();
        String projectName = projectName();
        if (existing != null && existing.processing() && StringUtils.hasText(existing.assetId())) {
            return poll(storageKey, existing.assetId(), groupIdOf(existing), projectName);
        }
        String url = objectStorage.presignGet(storageKey, PRESIGN_TTL);
        if (!publicHttps(url)) {
            throw new BizException(ErrorCode.BAD_REQUEST, NO_PUBLIC_URL);
        }
        if (existing != null && StringUtils.hasText(existing.assetId())) {
            deleteQuietly(existing.assetId(), projectName);
        }
        String groupId = ensureGroup(projectName);
        String assetId = api.createAsset(groupId, url.trim(), assetName(asset), projectName);
        ArkAssetRefs.save(objectStorage, objectMapper, storageKey,
                new ArkAssetRefs.Ref(assetId, "Processing", "", groupId));
        return poll(storageKey, assetId, groupId, projectName);
    }

    public ArkAssetRefs.Ref lookup(String storageKey) {
        return ArkAssetRefs.load(objectStorage, objectMapper, storageKey);
    }

    /** 生成记录或角色被删掉时释放素材，避免占配额。已经审核通过的其他图片保留。 */
    public void release(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return;
        }
        try {
            ArkAssetRefs.Ref ref = ArkAssetRefs.load(objectStorage, objectMapper, storageKey);
            if (ref != null && StringUtils.hasText(ref.assetId()) && credentialsReady()) {
                deleteQuietly(ref.assetId(), projectName());
            }
            ArkAssetRefs.deleteSidecar(objectStorage, storageKey);
        } catch (RuntimeException e) {
            log.warn("release ark asset failed, key={}: {}", storageKey, e.getMessage());
        }
    }

    private DramaAssetReviewResp poll(String storageKey, String assetId, String groupId, String projectName) {
        long started = clock.getAsLong();
        while (true) {
            ArkAssetView view = api.getAsset(assetId, projectName);
            String status = view.canonicalStatus();
            String error = view.failed() ? view.errorText() : "";
            ArkAssetRefs.save(objectStorage, objectMapper, storageKey,
                    new ArkAssetRefs.Ref(assetId, status, error, groupId));
            if (view.active()) {
                return active(storageKey, assetId);
            }
            if (view.failed()) {
                throw new BizException(ErrorCode.BAD_REQUEST, view.errorText());
            }
            long elapsed = clock.getAsLong() - started;
            if (elapsed >= deadline.toMillis()) {
                throw new BizException(ErrorCode.BAD_REQUEST, TIMEOUT_MESSAGE);
            }
            long remaining = deadline.toMillis() - elapsed;
            long wait = Math.min(Math.max(0, pollInterval.toMillis()), remaining);
            try {
                pause.pause(Duration.ofMillis(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.BAD_REQUEST, "角色审核已中断");
            }
        }
    }

    private String ensureGroup(String projectName) {
        String configured = text(ark.getAssetGroupId());
        if (configured != null) {
            return configured;
        }
        synchronized (groupLock) {
            configured = text(ark.getAssetGroupId());
            if (configured != null) {
                return configured;
            }
            String stored = ArkAssetGroups.read(objectStorage, objectMapper, projectName);
            if (stored != null) {
                ark.setAssetGroupId(stored);
                return stored;
            }
            String created = api.createAssetGroup(
                    ArkAssetGroups.NAME, ArkAssetGroups.DESCRIPTION, ArkAssetGroups.TYPE, projectName);
            log.info("created ark asset group {}", created);
            ArkAssetGroups.write(objectStorage, objectMapper, created, projectName);
            ark.setAssetGroupId(created);
            return created;
        }
    }

    private void deleteQuietly(String assetId, String projectName) {
        try {
            api.deleteAsset(assetId, projectName);
        } catch (RuntimeException e) {
            log.warn("delete ark asset failed, assetId={}: {}", assetId, e.getMessage());
        }
    }

    private String groupIdOf(ArkAssetRefs.Ref existing) {
        if (existing != null && StringUtils.hasText(existing.groupId())) {
            return existing.groupId().trim();
        }
        String configured = text(ark.getAssetGroupId());
        return configured == null ? "" : configured;
    }

    private void assertCredentials() {
        if (!credentialsReady()) {
            throw new BizException(ErrorCode.BAD_REQUEST, ArkAssetClient.MISSING_KEY_MESSAGE);
        }
    }

    private boolean credentialsReady() {
        return StringUtils.hasText(accessKey()) && StringUtils.hasText(secretKey());
    }

    private String accessKey() {
        String stored = credentials == null ? null : credentials.accessKey();
        if (StringUtils.hasText(stored)) {
            return stored.trim();
        }
        return text(ark.getAccessKey());
    }

    private String secretKey() {
        String stored = credentials == null ? null : credentials.secretKey();
        if (StringUtils.hasText(stored)) {
            return stored.trim();
        }
        return text(ark.getSecretKey());
    }

    private String projectName() {
        String project = text(ark.getProjectName());
        return project == null ? "default" : project;
    }

    static String assetName(DramaAsset asset) {
        long seriesId = asset.getSeriesId() == null ? 0L : asset.getSeriesId();
        long assetId = asset.getId() == null ? 0L : asset.getId();
        String name = "drama-" + seriesId + "-" + assetId;
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    static boolean publicHttps(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("https://") && value.length() > "https://".length();
    }

    private static DramaAssetReviewResp active(String storageKey, String assetId) {
        DramaAssetReviewResp resp = new DramaAssetReviewResp();
        resp.setStatus("Active");
        resp.setAssetId(assetId);
        resp.setStorageKey(storageKey);
        return resp;
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(Math.max(0, duration.toMillis()));
    }

    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }
}
