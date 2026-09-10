package cn.aigc.dit.server.drama.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 剧本理解层实现。当前覆盖规则分场；文本模型路径接入后由本类按系列判定引擎。
 * <p>
 * 资产草稿与分镜草稿沿用 {@link DramaTextService} 的 default 规则实现。
 */
@Slf4j
@Service
public class DramaTextServiceImpl implements DramaTextService {

    @Override
    public String engine(long seriesId) {
        return ENGINE_RULES;
    }

    @Override
    public ScriptDoc parseScript(long seriesId, String raw) {
        ScriptDoc doc = ScriptParser.parse(raw);
        log.info("[drama-text] 规则分场 seriesId={} scenes={}", seriesId, doc.getScenes().size());
        return doc;
    }
}
