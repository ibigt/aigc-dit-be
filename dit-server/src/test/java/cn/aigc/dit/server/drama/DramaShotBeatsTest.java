package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaShotBeatsTest {

    @Test
    void storedDialogueMatchesBeatLinesInOrder() {
        ShotDraftList.ShotDraft draft = new ShotDraftList.ShotDraft();
        draft.setDialogue("整段案情堆在最后一句。");
        draft.setShotBeats(beats(
                beat("中景，跟拍", "局长推门进入。", "门开", ""),
                beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", ""),
                beat("特写，静止", "局长盯着洛白。", "接档案落桌", "局长（愤怒）：你给我站住！")));
        DramaShotBeats normalized = DramaShotBeats.normalize(draft.getShotBeats());
        draft.setShotBeats(normalized);
        DramaShotBeats.syncDraftDialogue(draft);

        DramaShot shot = new DramaShot();
        shot.setShotBeats(draft.getShotBeats());
        shot.setDialogue(draft.getDialogue());

        assertEquals("局长（愤怒）：你给我站住！", shot.getDialogue());
        assertEquals(shot.getDialogue(), DramaShotBeats.joinedDialogue(shot.getShotBeats()));
    }

    @Test
    void draftPersistPicksUpCharactersAndSlammedPropsFromBeats() {
        DramaShot shot = new DramaShot();
        shot.setNarrativeGoal("问责。");
        shot.setCharacterNames(new ArrayList<>());
        shot.setPropNames(new ArrayList<>());
        shot.setShotBeats(beats(
                beat("中景，跟拍", "局长推门进入。", "门开", ""),
                beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", ""),
                beat("特写，静止", "局长盯着洛白。", "接档案落桌", "洛白：我在。")));

        DramaAsset chief = asset("局长", DramaContract.ASSET_TYPE_CHARACTER);
        DramaAsset luo = asset("洛白", DramaContract.ASSET_TYPE_CHARACTER);
        DramaAsset file = asset("档案", DramaContract.ASSET_TYPE_PROP);
        Map<Long, DramaAsset> assets = Map.of(1L, chief, 2L, luo, 3L, file);

        DramaStoryboardService.alignMentionedCharacters(shot, assets);
        DramaStoryboardService.alignMentionedProps(shot, assets);

        assertEquals(java.util.List.of("局长", "洛白"), shot.getCharacterNames());
        assertTrue(shot.getPropNames().contains("档案"));
    }

    private static DramaAsset asset(String name, String type) {
        DramaAsset asset = new DramaAsset();
        asset.setName(name);
        asset.setType(type);
        return asset;
    }

    private static DramaShotBeats beats(DramaShotBeats.Beat... items) {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking("局长在门口，档案在桌上。");
        beats.setBeats(new ArrayList<>(java.util.List.of(items)));
        return beats;
    }

    private static DramaShotBeats.Beat beat(String camera, String action, String continuity, String dialogue) {
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera(camera);
        beat.setAction(action);
        beat.setContinuity(continuity);
        beat.setDialogue(dialogue);
        return beat;
    }
}
