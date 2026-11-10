package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.describe.ImageDescribeClient;
import cn.aigc.dit.generate.describe.ImageDescribePrompts;
import cn.aigc.dit.generate.describe.ImageDescribeResult;
import cn.aigc.dit.generate.seedream.SeedreamCharacterBinds;
import cn.aigc.dit.generate.seedream.SeedreamGenerateProvider;
import cn.aigc.dit.generate.seedream.SeedreamOriginUrls;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeedreamShotBindServiceTest {

    private static final String ORIGINAL = "users/7/outputs/249/1.png";
    private static final String SCENE = "users/7/scene.jpg";
    private static final String AUDIO = "users/7/voice.mp3";

    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private DramaAssetMapper assetMapper;
    @Mock
    private DramaAssetGenerationMapper generationMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private DramaAssetService dramaAssetService;
    @Mock
    private ImageDescribeClient imageDescribeClient;
    @Mock
    private SeedreamGenerateProvider seedream;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemStorage storage = new MemStorage();
    private SeedreamShotBindService service;
    private DramaAsset character;
    private String expectedImageKey = ORIGINAL;

    @BeforeEach
    void setUp() {
        service = new SeedreamShotBindService(
                seriesMapper, assetMapper, generationMapper, genTaskMapper, dramaAssetService,
                storage, objectMapper, imageDescribeClient, seedream);
        character = new DramaAsset();
        character.setId(9L);
        character.setUserId(7L);
        character.setEpisodeId(31L);
        character.setType(DramaContract.ASSET_TYPE_CHARACTER);
        character.setImageStorageKey(ORIGINAL);
        character.setSelectedGenerationId(11L);
        when(assetMapper.selectList(any())).thenReturn(List.of(character));
        when(generationMapper.selectList(any())).thenReturn(List.of());
        storage.put(ORIGINAL, new byte[]{1, 2, 3}, "image/png");
    }

    @Test
    void createsSheetBesideOriginalAndLeavesSelectedImage() {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        stubGenerate();
        GenTask task = shot("seedance-2.0", "r2v");

        service.prepare(task, officialModel("seedance-2.0"));

        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), refKey(task, 0));
        assertEquals(SCENE, refKey(task, 1));
        assertEquals(AUDIO, refKey(task, 2));
        assertSelectedImageUnchanged();
        ArgumentCaptor<GenerateRequest> captor = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(seedream).writeTextToImage(captor.capture(), eq(SeedreamCharacterBinds.imageKey(ORIGINAL)));
        assertEquals(roundtripPrompt(), captor.getValue().getPrompt());
        assertTrue(captor.getValue().getReferences() == null || captor.getValue().getReferences().isEmpty());
        verify(imageDescribeClient).describe(any(), eq("image/png"),
                eq(ImageDescribePrompts.CHARACTER_REALISM), any(), eq("ark-key"), isNull());
        verify(assetMapper, never()).updateById(any(DramaAsset.class));
        verify(generationMapper, never()).insert(any(DramaAssetGeneration.class));
        SeedreamCharacterBinds.Bind bind = SeedreamCharacterBinds.read(storage, objectMapper, ORIGINAL);
        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), bind.imageKey());
        assertTrue(SeedreamCharacterBinds.fresh(bind, Instant.now()));
    }

    @Test
    void otherRealismUsesTheSameRoundtripPrompt() {
        stubSeries(DramaStyles.GUOFENG_3D);
        stubSeedreamEnabled();
        stubGenerate();

        service.prepare(shot("seedance-2.0", "r2v"), officialModel("seedance-2.0"));

        ArgumentCaptor<GenerateRequest> captor = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(seedream).writeTextToImage(captor.capture(), any());
        assertEquals(roundtripPrompt(), captor.getValue().getPrompt());
        assertFalse(captor.getValue().getPrompt().contains("固定四格设定拼版"));
        assertFalse(captor.getValue().getPrompt().contains("正脸近景"));
        assertSelectedImageUnchanged();
    }

    @Test
    void legacyRealistic3dUsesTheSameRoundtripPrompt() {
        stubSeries(DramaStyles.LEGACY_REALISTIC_3D);
        stubSeedreamEnabled();
        stubGenerate();

        service.prepare(shot("seedance-2.5", "r2v"), officialModel("seedance-2.5"));

        ArgumentCaptor<GenerateRequest> captor = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(seedream).writeTextToImage(captor.capture(), any());
        assertEquals(roundtripPrompt(), captor.getValue().getPrompt());
        assertSelectedImageUnchanged();
    }

    @Test
    void reusesBindInsideThirtyDays() {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        SeedreamCharacterBinds.save(storage, objectMapper, ORIGINAL, SeedreamCharacterBinds.imageKey(ORIGINAL),
                Instant.now().minus(Duration.ofDays(29)));
        storage.put(SeedreamCharacterBinds.imageKey(ORIGINAL), new byte[]{9}, "image/png");
        GenTask task = shot("seedance-2.5", "r2v");

        service.prepare(task, officialModel("seedance-2.5"));

        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), refKey(task, 0));
        assertEquals(SCENE, refKey(task, 1));
        verify(imageDescribeClient, never()).describe(any(), any(), any(), any(), any(), any());
        verify(seedream, never()).writeTextToImage(any(), any());
        assertTrue(storage.deleted.isEmpty());
        assertSelectedImageUnchanged();
    }

    @Test
    void expiredBindIsDeletedBeforeRecreate() {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        stubGenerate();
        SeedreamCharacterBinds.save(storage, objectMapper, ORIGINAL, SeedreamCharacterBinds.imageKey(ORIGINAL),
                Instant.now().minus(Duration.ofDays(31)));
        storage.put(SeedreamCharacterBinds.imageKey(ORIGINAL), new byte[]{9}, "image/png");
        storage.deleted.clear();

        service.prepare(shot("seedance-2.0", "r2v"), officialModel("seedance-2.0"));

        int imageDelete = storage.events.indexOf("delete:" + SeedreamCharacterBinds.imageKey(ORIGINAL));
        assertTrue(imageDelete >= 0);
        assertEquals("delete:" + SeedreamCharacterBinds.originKey(ORIGINAL), storage.events.get(imageDelete + 1));
        assertEquals("delete:" + SeedreamCharacterBinds.bindKey(ORIGINAL), storage.events.get(imageDelete + 2));
        assertTrue(imageDelete < storage.events.indexOf("describe"));
        assertTrue(storage.events.indexOf("describe") < storage.events.indexOf("generate"));
        verify(seedream, times(1)).writeTextToImage(any(), eq(SeedreamCharacterBinds.imageKey(ORIGINAL)));
        assertSelectedImageUnchanged();
    }

    @Test
    void nativeSeedreamInsideThirtyDaysStaysOnOriginalKey() {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        SeedreamOriginUrls.save(storage, objectMapper, ORIGINAL, "https://cdn.example/native.png",
                Instant.now().minus(Duration.ofDays(10)));
        GenTask task = shot("seedance-2.0", "r2v");

        service.prepare(task, officialModel("seedance-2.0"));

        assertEquals(ORIGINAL, refKey(task, 0));
        verify(genTaskMapper, never()).updateById(any(GenTask.class));
        verify(imageDescribeClient, never()).describe(any(), any(), any(), any(), any(), any());
        verify(seedream, never()).writeTextToImage(any(), any());
        assertSelectedImageUnchanged();
    }

    @Test
    void nativeSeedreamOlderThanThirtyDaysIsRedone() {
        stubSeries(DramaStyles.KDRAMA_SOFT);
        stubSeedreamEnabled();
        stubGenerate();
        SeedreamOriginUrls.save(storage, objectMapper, ORIGINAL, "https://cdn.example/old.png",
                Instant.now().minus(Duration.ofDays(31)));
        GenTask task = shot("seedance-2.0", "r2v");

        service.prepare(task, officialModel("seedance-2.0"));

        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), refKey(task, 0));
        verify(seedream).writeTextToImage(any(), eq(SeedreamCharacterBinds.imageKey(ORIGINAL)));
        assertEquals(ORIGINAL, character.getImageStorageKey());
        assertEquals(11L, character.getSelectedGenerationId());
    }

    @Test
    void failsWhenSeedreamIsDisabledSoOriginalIsNotSent() {
        stubSeries(DramaStyles.LIVE_REALISM);
        when(dramaAssetService.resolveSeedreamConvertCatalogModel())
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "转资产图需要管理端启用官方 Seedream 5.0 模型"));
        SeedreamCharacterBinds.save(storage, objectMapper, ORIGINAL, SeedreamCharacterBinds.imageKey(ORIGINAL),
                Instant.now());
        storage.put(SeedreamCharacterBinds.imageKey(ORIGINAL), new byte[]{9}, "image/png");
        GenTask task = shot("seedance-2.0", "r2v");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.prepare(task, officialModel("seedance-2.0")));

        assertTrue(error.getMessage().contains("不能把角色原图送去出片"));
        assertEquals(ORIGINAL, refKey(task, 0));
        verify(seedream, never()).writeTextToImage(any(), any());
        assertTrue(storage.deleted.isEmpty());
    }

    @Test
    void skipsAnimeRunningHubFramesAndNonCharacterRefs() {
        stubSeedreamEnabled();
        stubSeries(DramaStyles.ANIME_COMIC);
        GenTask anime = shot("seedance-2.0", "r2v");
        service.prepare(anime, officialModel("seedance-2.0"));
        assertEquals(ORIGINAL, refKey(anime, 0));

        stubSeries(DramaStyles.LIVE_REALISM);
        GenTask runningHub = shot("seedance-2", "r2v");
        AiModel hub = officialModel("seedance-2");
        hub.setModelKey("doubao-seedance-2-0-260128");
        service.prepare(runningHub, hub);
        assertEquals(ORIGINAL, refKey(runningHub, 0));

        GenTask frames = shot("seedance-2.0", "i2v");
        service.prepare(frames, officialModel("seedance-2.0"));
        assertEquals(ORIGINAL, refKey(frames, 0));

        GenTask quick = shot("seedance-2.0", "r2v");
        quick.getParams().put("source", "快速创作");
        service.prepare(quick, officialModel("seedance-2.0"));
        assertEquals(ORIGINAL, refKey(quick, 0));

        verify(imageDescribeClient, never()).describe(any(), any(), any(), any(), any(), any());
        verify(seedream, never()).writeTextToImage(any(), any());
    }

    @Test
    void convertsHistoricalCharacterGenerationWithoutTouchingCurrentPick() {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        stubGenerate();
        character.setImageStorageKey("users/7/outputs/current.png");
        expectedImageKey = "users/7/outputs/current.png";
        DramaAssetGeneration older = new DramaAssetGeneration();
        older.setAssetId(9L);
        older.setUserId(7L);
        older.setStorageKey(ORIGINAL);
        when(generationMapper.selectList(any())).thenReturn(List.of(older));
        GenTask task = shot("seedance-2.0", "r2v");

        service.prepare(task, officialModel("seedance-2.0"));

        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), refKey(task, 0));
        assertEquals("users/7/outputs/current.png", character.getImageStorageKey());
        assertEquals(11L, character.getSelectedGenerationId());
        verify(generationMapper, never()).insert(any(DramaAssetGeneration.class));
        verify(assetMapper, never()).updateById(any(DramaAsset.class));
    }

    @Test
    void sameOriginalIsConvertedOnceUnderLock() throws Exception {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(imageDescribeClient.describe(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ImageDescribeResult("二十五岁短发", "圆脸，白衬衫，湿身", "raw"));
        when(dramaAssetService.resolveConvertArkCredential(any()))
                .thenReturn(new DramaAssetService.ConvertArkCredential("ark-key", "https://ark.example/api/plan/v3", null));
        when(dramaAssetService.configuredSeedreamConvertModelKey()).thenReturn("doubao-seedream-5-0-pro");
        when(seedream.writeTextToImage(any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                inside.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            String key = invocation.getArgument(1);
            storage.put(key, new byte[]{4}, "image/png");
            storage.events.add("generate");
            return key;
        });
        GenTask first = shot("seedance-2.0", "r2v");
        GenTask second = shot("seedance-2.0", "r2v");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = pool.submit(() -> service.prepare(first, officialModel("seedance-2.0")));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            Future<?> right = pool.submit(() -> service.prepare(second, officialModel("seedance-2.0")));
            release.countDown();
            left.get(5, TimeUnit.SECONDS);
            right.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, calls.get());
        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), refKey(first, 0));
        assertEquals(SeedreamCharacterBinds.imageKey(ORIGINAL), refKey(second, 0));
        assertSelectedImageUnchanged();
    }

    @Test
    void generateFailureKeepsOriginalReference() {
        stubSeries(DramaStyles.LIVE_REALISM);
        stubSeedreamEnabled();
        when(dramaAssetService.resolveConvertArkCredential(any()))
                .thenReturn(new DramaAssetService.ConvertArkCredential("ark-key", "https://ark.example/api/plan/v3", null));
        when(imageDescribeClient.describe(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ImageDescribeResult("二十五岁短发", "圆脸，白衬衫，湿身", "raw"));
        when(seedream.writeTextToImage(any(), any())).thenThrow(new IllegalStateException("ark down"));
        GenTask task = shot("seedance-2.0", "r2v");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.prepare(task, officialModel("seedance-2.0")));

        assertTrue(error.getMessage().contains("角色定妆图生成失败"));
        assertEquals(ORIGINAL, refKey(task, 0));
        verify(genTaskMapper, never()).updateById(any(GenTask.class));
        assertSelectedImageUnchanged();
    }

    private static String roundtripPrompt() {
        return new ImageDescribeResult("二十五岁短发", "圆脸，白衬衫，湿身", "raw").realisticPrompt();
    }

    private void stubSeries(String styleKey) {
        DramaSeries series = new DramaSeries();
        series.setId(3L);
        series.setStyleKey(styleKey);
        series.setAspectRatio("16:9");
        when(seriesMapper.selectById(3L)).thenReturn(series);
    }

    private void stubSeedreamEnabled() {
        AiModel seedreamModel = new AiModel();
        seedreamModel.setId(8L);
        seedreamModel.setEnabled(true);
        seedreamModel.setModelKey("doubao-seedream-5-0-pro");
        when(dramaAssetService.resolveSeedreamConvertCatalogModel()).thenReturn(seedreamModel);
    }

    private void stubGenerate() {
        when(dramaAssetService.resolveConvertArkCredential(any()))
                .thenReturn(new DramaAssetService.ConvertArkCredential(
                        "ark-key", "https://ark.example/api/plan/v3", null));
        when(dramaAssetService.configuredSeedreamConvertModelKey()).thenReturn("doubao-seedream-5-0-pro");
        when(imageDescribeClient.describe(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            storage.events.add("describe");
            return new ImageDescribeResult("二十五岁短发", "圆脸，白衬衫，湿身", "raw");
        });
        when(seedream.writeTextToImage(any(), any())).thenAnswer(invocation -> {
            storage.events.add("generate");
            String key = invocation.getArgument(1);
            storage.put(key, new byte[]{4}, "image/png");
            return key;
        });
    }

    private static AiModel officialModel(String adapter) {
        AiModel model = new AiModel();
        model.setAdapterKey(adapter);
        model.setModelKey("seedance-2.5".equals(adapter)
                ? "doubao-seedance-2-5-260628"
                : "doubao-seedance-2-0-260128");
        model.setCategory("video");
        return model;
    }

    private static GenTask shot(String adapter, String mode) {
        GenTask task = new GenTask();
        task.setId(50L);
        task.setUserId(7L);
        task.setCategory("video");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", "漫剧");
        params.put("shotId", 5L);
        params.put("seriesId", 3L);
        params.put("episodeId", 31L);
        params.put("adapterKey", adapter);
        params.put("videoGenerateMode", mode);
        params.put("mode", mode);
        List<Map<String, String>> refs = new ArrayList<>();
        refs.add(ref("image", ORIGINAL));
        refs.add(ref("image", SCENE));
        refs.add(ref("audio", AUDIO));
        params.put("references", refs);
        task.setParams(params);
        return task;
    }

    private static Map<String, String> ref(String type, String key) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("storageKey", key);
        return item;
    }

    @SuppressWarnings("unchecked")
    private static String refKey(GenTask task, int index) {
        List<Map<String, String>> refs = (List<Map<String, String>>) task.getParams().get("references");
        return refs.get(index).get("storageKey");
    }

    private void assertSelectedImageUnchanged() {
        assertEquals(expectedImageKey, character.getImageStorageKey());
        assertEquals(11L, character.getSelectedGenerationId());
        verify(assetMapper, never()).updateById(any(DramaAsset.class));
        verify(generationMapper, never()).insert(any(DramaAssetGeneration.class));
    }

    private static final class MemStorage implements ObjectStorage {
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final List<String> deleted = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void put(String storageKey, InputStream content, long contentLength, String contentType) {
            try {
                files.put(storageKey, content.readAllBytes());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream get(String storageKey) {
            byte[] data = files.get(storageKey);
            if (data == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "对象不存在");
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public byte[] getBytes(String storageKey) {
            return files.get(storageKey);
        }

        @Override
        public boolean exists(String storageKey) {
            return files.containsKey(storageKey);
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
            events.add("delete:" + storageKey);
            files.remove(storageKey);
        }

        @Override
        public String presignGet(String storageKey, Duration expiry) {
            return storageKey;
        }

        @Override
        public String presignPut(String storageKey, Duration expiry, String contentType) {
            return storageKey;
        }
    }
}
