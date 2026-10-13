package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ark.ArkAssetApi;
import cn.aigc.dit.generate.ark.ArkAssetGroups;
import cn.aigc.dit.generate.ark.ArkAssetRefs;
import cn.aigc.dit.generate.ark.ArkAssetView;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.model.resp.DramaAssetReviewResp;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArkCharacterReviewTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong clock = new AtomicLong();

    @Test
    void firstReviewCreatesOneGroupThenPollsToActive() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        GenerateProperties properties = credentials();
        ArkCharacterReview review = review(storage, properties, api, Duration.ofSeconds(120));

        DramaAssetReviewResp first = review.review(character("users/7/role-a.png", 21L, 9L));
        DramaAssetReviewResp second = review.review(character("users/7/role-b.png", 21L, 10L));

        assertEquals("Active", first.getStatus());
        assertEquals("asset-20241012-demo", first.getAssetId());
        assertEquals("asset://asset-20241012-demo", ArkAssetRefs.activeUri(storage, mapper, first.getStorageKey()));
        assertEquals("Active", second.getStatus());
        assertEquals(1, api.groups);
        assertEquals(2, api.creates);
        assertEquals(ArkAssetGroups.NAME, api.groupNames.get(0));
        assertEquals(ArkAssetGroups.DESCRIPTION, api.groupDescriptions.get(0));
        assertEquals("AIGC", api.groupTypes.get(0));
        assertEquals("default", api.projects.get(0));
        assertEquals("drama-21-9", api.names.get(0));
        assertEquals("drama-21-10", api.names.get(1));
        assertTrue(api.urls.get(0).startsWith("https://cdn.example/"));
        assertEquals(ArkCharacterReview.PRESIGN_TTL, storage.expiry);
        assertEquals("group-20241012-aigc", properties.getArk().getAssetGroupId());
        assertEquals("group-20241012-aigc", ArkAssetGroups.read(storage, mapper, "default"));
    }

    @Test
    void configuredGroupIsReused() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        GenerateProperties properties = credentials();
        properties.getArk().setAssetGroupId("group-configured");
        ArkCharacterReview review = review(storage, properties, api, Duration.ofSeconds(120));

        review.review(character("users/7/role-a.png", 21L, 9L));

        assertEquals(0, api.groups);
        assertEquals("group-configured", api.groupsUsed.get(0));
    }

    @Test
    void failedReturnsArkTextAndDoesNotCreateAgain() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        api.views.add(new ArkAssetView("asset-1", "Failed", "InputImageSensitiveContentDetected", "人脸不一致"));
        ArkCharacterReview review = review(storage, credentials(), api, Duration.ofSeconds(120));

        BizException error = assertThrows(BizException.class,
                () -> review.review(character("users/7/role-a.png", 21L, 9L)));

        assertEquals("InputImageSensitiveContentDetected: 人脸不一致", error.getMessage());
        assertFalse(error.getMessage().contains("已停止出片"));
        assertEquals(1, api.creates);
        assertEquals(1, api.gets);
        ArkAssetRefs.Ref saved = ArkAssetRefs.load(storage, mapper, "users/7/role-a.png");
        assertEquals("Failed", saved.status());
        assertEquals(error.getMessage(), saved.error());
    }

    @Test
    void realPersonWordingFromArkIsNotReplaced() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        api.views.add(new ArkAssetView("asset-1", "Failed", "", "参考图包含真人脸"));
        ArkCharacterReview review = review(storage, credentials(), api, Duration.ofSeconds(120));

        BizException error = assertThrows(BizException.class,
                () -> review.review(character("users/7/role-a.png", 21L, 9L)));

        assertEquals("参考图包含真人脸", error.getMessage());
        assertEquals(1, api.creates);
    }

    @Test
    void processingResumesWithoutCreatingAnotherAsset() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        api.views.add(new ArkAssetView("asset-keep", "Processing", "", ""));
        api.views.add(new ArkAssetView("asset-keep", "Active", "", ""));
        ArkAssetRefs.save(storage, mapper, "users/7/role-a.png",
                new ArkAssetRefs.Ref("asset-keep", "Processing", "", "group-configured"));
        GenerateProperties properties = credentials();
        properties.getArk().setAssetGroupId("group-configured");
        ArkCharacterReview review = review(storage, properties, api, Duration.ofSeconds(120));

        DramaAssetReviewResp result = review.review(character("users/7/role-a.png", 21L, 9L));

        assertEquals("asset-keep", result.getAssetId());
        assertEquals(0, api.creates);
        assertEquals(2, api.gets);
        assertEquals(0, api.deletes);
    }

    @Test
    void timeoutKeepsProcessingSoTheNextClickCanResume() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        api.always = new ArkAssetView("asset-1", "Processing", "", "");
        ArkCharacterReview review = review(storage, credentials(), api, Duration.ofSeconds(6));

        BizException error = assertThrows(BizException.class,
                () -> review.review(character("users/7/role-a.png", 21L, 9L)));

        assertEquals(ArkCharacterReview.TIMEOUT_MESSAGE, error.getMessage());
        assertEquals(1, api.creates);
        assertEquals("Processing", ArkAssetRefs.load(storage, mapper, "users/7/role-a.png").status());
    }

    @Test
    void retryAfterFailureDeletesTheOldAssetOnce() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        api.views.add(new ArkAssetView("asset-old", "Failed", "InputImageSensitiveContentDetected", "人脸不一致"));
        ArkCharacterReview review = review(storage, credentials(), api, Duration.ofSeconds(120));
        assertThrows(BizException.class, () -> review.review(character("users/7/role-a.png", 21L, 9L)));

        api.views.clear();
        DramaAssetReviewResp result = review.review(character("users/7/role-a.png", 21L, 9L));

        assertEquals("Active", result.getStatus());
        assertEquals(2, api.creates);
        assertEquals(1, api.deletes);
        assertEquals("asset-20241012-demo", api.deleted.get(0));
    }

    @Test
    void localStorageCannotBeRegistered() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir);
        FakeAssets api = new FakeAssets();
        ArkCharacterReview review = review(storage, credentials(), api, Duration.ofSeconds(120));

        BizException error = assertThrows(BizException.class,
                () -> review.review(character("users/7/role-a.png", 21L, 9L)));

        assertEquals(ArkCharacterReview.NO_PUBLIC_URL, error.getMessage());
        assertEquals(0, api.groups);
        assertEquals(0, api.creates);
    }

    @Test
    void sceneAndMissingImageAreRejected() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        ArkCharacterReview review = review(storage, credentials(), api, Duration.ofSeconds(120));
        DramaAsset scene = character("users/7/scene.png", 21L, 3L);
        scene.setType(DramaContract.ASSET_TYPE_SCENE);

        assertEquals("只有角色资产可以审核",
                assertThrows(BizException.class, () -> review.review(scene)).getMessage());
        DramaAsset empty = character(null, 21L, 9L);
        assertEquals("请先选择一张角色图",
                assertThrows(BizException.class, () -> review.review(empty)).getMessage());
        assertEquals(0, api.creates);
    }

    @Test
    void alreadyActiveSkipsTheApi() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        ArkAssetRefs.save(storage, mapper, "users/7/role-a.png",
                new ArkAssetRefs.Ref("asset-ready", "Active", "", "group-1"));
        GenerateProperties properties = new GenerateProperties();
        ArkCharacterReview review = review(storage, properties, api, Duration.ofSeconds(120));

        DramaAssetReviewResp result = review.review(character("users/7/role-a.png", 21L, 9L));

        assertEquals("asset-ready", result.getAssetId());
        assertEquals(0, api.creates);
        assertEquals(0, api.gets);
    }

    @Test
    void releaseDeletesTheRemoteAssetAndSidecar() {
        PublicStorage storage = new PublicStorage(tempDir);
        FakeAssets api = new FakeAssets();
        GenerateProperties properties = credentials();
        ArkAssetRefs.save(storage, mapper, "users/7/role-a.png",
                new ArkAssetRefs.Ref("asset-ready", "Active", "", "group-1"));
        ArkCharacterReview review = review(storage, properties, api, Duration.ofSeconds(120));

        review.release("users/7/role-a.png");

        assertEquals(List.of("asset-ready"), api.deleted);
        assertNull(ArkAssetRefs.load(storage, mapper, "users/7/role-a.png"));
    }

    private ArkCharacterReview review(cn.aigc.dit.storage.ObjectStorage storage, GenerateProperties properties,
                                      FakeAssets api, Duration deadline) {
        return new ArkCharacterReview(storage, properties, api, mapper, clock::get,
                duration -> clock.addAndGet(duration.toMillis()),
                ArkCharacterReview.POLL_INTERVAL, deadline);
    }

    private static GenerateProperties credentials() {
        GenerateProperties properties = new GenerateProperties();
        properties.getArk().setAccessKey("AKLTTEST");
        properties.getArk().setSecretKey("secret-key");
        properties.getArk().setProjectName("default");
        return properties;
    }

    private static DramaAsset character(String storageKey, Long seriesId, Long assetId) {
        DramaAsset asset = new DramaAsset();
        asset.setId(assetId);
        asset.setSeriesId(seriesId);
        asset.setType(DramaContract.ASSET_TYPE_CHARACTER);
        asset.setImageStorageKey(storageKey);
        return asset;
    }

    private static final class PublicStorage extends LocalObjectStorage {
        private Duration expiry;

        private PublicStorage(Path root) {
            super(root);
        }

        @Override
        public String presignGet(String storageKey, Duration expiry) {
            this.expiry = expiry;
            return "https://cdn.example/" + storageKey;
        }
    }

    private static final class FakeAssets implements ArkAssetApi {
        private int groups;
        private int creates;
        private int gets;
        private int deletes;
        private final List<String> groupNames = new ArrayList<>();
        private final List<String> groupDescriptions = new ArrayList<>();
        private final List<String> groupTypes = new ArrayList<>();
        private final List<String> projects = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<String> urls = new ArrayList<>();
        private final List<String> groupsUsed = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private final Queue<ArkAssetView> views = new ArrayDeque<>();
        private ArkAssetView always;

        @Override
        public String createAssetGroup(String name, String description, String groupType, String projectName) {
            groups++;
            groupNames.add(name);
            groupDescriptions.add(description);
            groupTypes.add(groupType);
            projects.add(projectName);
            return "group-20241012-aigc";
        }

        @Override
        public String createAsset(String groupId, String url, String name, String projectName) {
            creates++;
            groupsUsed.add(groupId);
            urls.add(url);
            names.add(name);
            return "asset-20241012-demo";
        }

        @Override
        public ArkAssetView getAsset(String assetId, String projectName) {
            gets++;
            ArkAssetView next = always != null ? always : (views.isEmpty() ? null : views.remove());
            if (next == null) {
                return new ArkAssetView(assetId, "Active", "", "");
            }
            return new ArkAssetView(assetId, next.status(), next.errorCode(), next.errorMessage());
        }

        @Override
        public void deleteAsset(String assetId, String projectName) {
            deletes++;
            deleted.add(assetId);
        }
    }
}
