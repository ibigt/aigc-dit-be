package cn.aigc.dit.server.service;

import cn.aigc.dit.server.drama.ArkCharacterReview;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaRetentionServiceTest {

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private ArkCharacterReview characterReview;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private DramaAssetMapper dramaAssetMapper;
    @Mock
    private DramaAssetGenerationMapper dramaAssetGenerationMapper;
    @Mock
    private DramaShotMapper dramaShotMapper;
    @Mock
    private DramaShotGenerationMapper dramaShotGenerationMapper;
    @Mock
    private DramaShotCaptionMapper dramaShotCaptionMapper;
    @Mock
    private DramaEpisodeMapper dramaEpisodeMapper;

    @Test
    void deletesFileWhenCreationAndDramaAreGone() {
        MediaRetentionService service = service();

        service.purgeAfterCommit(7L, List.of("users/7/gen/only.png"));

        verify(characterReview).release("users/7/gen/only.png");
        verify(objectStorage).delete("users/7/gen/only.png");
    }

    @Test
    void keepsFileWhileCreationStillReferencesIt() {
        Creation creation = new Creation();
        creation.setUserId(7L);
        creation.setStorageKey("users/7/gen/keep.png");
        when(creationMapper.selectList(any())).thenReturn(List.of(creation));
        MediaRetentionService service = service();

        service.purgeAfterCommit(7L, List.of("users/7/gen/keep.png"));

        verify(objectStorage, never()).delete(any());
        verify(characterReview).release("users/7/gen/keep.png");
    }

    @Test
    void keepsFileAndArkReviewWhileDramaStillShowsIt() {
        DramaAssetGeneration generation = new DramaAssetGeneration();
        generation.setUserId(7L);
        generation.setStorageKey("users/7/gen/drama.png");
        when(dramaAssetGenerationMapper.selectList(any())).thenReturn(List.of(generation));
        MediaRetentionService service = service();

        service.purgeAfterCommit(7L, List.of("users/7/gen/drama.png"));

        verify(objectStorage, never()).delete(any());
        verify(characterReview, never()).release(any());
    }

    @Test
    void keepsLibraryFileAfterDramaCopyIsGone() {
        Asset library = new Asset();
        library.setUserId(7L);
        library.setStorageKey("users/7/library/shared.png");
        when(assetMapper.selectList(any())).thenReturn(List.of(library));
        MediaRetentionService service = service();

        service.purgeAfterCommit(7L, List.of("users/7/library/shared.png"));

        verify(objectStorage, never()).delete(any());
        verify(characterReview).release("users/7/library/shared.png");
    }

    private MediaRetentionService service() {
        return new MediaRetentionService(objectStorage, characterReview, creationMapper, assetMapper,
                dramaAssetMapper, dramaAssetGenerationMapper, dramaShotMapper, dramaShotGenerationMapper,
                dramaShotCaptionMapper, dramaEpisodeMapper);
    }
}
