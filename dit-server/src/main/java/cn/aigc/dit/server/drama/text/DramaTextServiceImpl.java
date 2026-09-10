package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 剧本理解层实现。按系列绑定的文本模型判定引擎；未绑定走规则分场，调用方无感。
 * <p>
 * 资产草稿与分镜草稿沿用 {@link DramaTextService} 的 default 规则实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaTextServiceImpl implements DramaTextService {

    /** 模型偶尔会多写键名；合同外的键一律忽略而不是让整次解析失败。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int LLM_JSON_RETRIES = 2;

    /**
     * 本线程最近一次 parseScript 的实际引擎。文本模型失败会静默回落规则，
     * 只用「决策谓词」报引擎会把回落也标成 llm，所以这里记下实际值供 {@link #engine(long)} 读取。
     * 调用方必须紧跟 parseScript 使用；按 seriesId 比对，避免读到上一条请求的残留。
     */
    private static final ThreadLocal<Long> PARSED_SERIES = new ThreadLocal<>();
    private static final ThreadLocal<String> PARSED_ENGINE = new ThreadLocal<>();

    private final TextChatGateway gateway;

    @Override
    public String engine(long seriesId) {
        if (Objects.equals(PARSED_SERIES.get(), seriesId) && StringUtils.hasText(PARSED_ENGINE.get())) {
            return PARSED_ENGINE.get();
        }
        return useLlm(seriesId) ? ENGINE_LLM : ENGINE_RULES;
    }

    @Override
    public ScriptDoc parseScript(long seriesId, String raw) {
        if (!useLlm(seriesId)) {
            return record(seriesId, ENGINE_RULES, ScriptParser.parse(raw));
        }
        ScriptDoc doc = null;
        try {
            doc = llmParse(seriesId, raw);
        } catch (BizException e) {
            if (TextChatGateway.BINDING_UNUSABLE.equals(e.getMessage())) {
                throw e;
            }
            log.warn("[drama-text] 文本模型调用失败，回落规则分场 seriesId={}: {}", seriesId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[drama-text] 文本模型异常，回落规则分场 seriesId={}", seriesId, e);
        }
        if (doc == null) {
            return record(seriesId, ENGINE_RULES, ScriptParser.parse(raw));
        }
        return record(seriesId, ENGINE_LLM, doc);
    }

    /**
     * 只认系列绑定，不认环境变量：{@code drama_series.text_model_id → ai_model}。
     * 用 {@code resolve} 而非 {@code llmAvailable}——后者把「绑了但已停用」压成 false，
     * 会让用户被静默降级成规则分场，而 {@link TextChatGateway#BINDING_UNUSABLE} 的意图是提示去换绑。
     */
    private boolean useLlm(long seriesId) {
        return gateway.resolve(seriesId).isPresent();
    }

    private ScriptDoc llmParse(long seriesId, String raw) {
        TextChatRequest req = new TextChatRequest(
                DramaTextPrompts.scriptSystem(), DramaTextPrompts.scriptUser(raw), true);
        for (int attempt = 1; attempt <= LLM_JSON_RETRIES; attempt++) {
            ScriptDoc doc = readScript(gateway.chat(seriesId, req).getContent());
            if (doc != null) {
                if (attempt > 1) {
                    log.info("[drama-text] 第 {} 次输出符合合同 seriesId={}", attempt, seriesId);
                }
                return doc;
            }
            log.warn("[drama-text] 输出不符合 ScriptDoc 合同 seriesId={} attempt={}", seriesId, attempt);
        }
        return null;
    }

    static ScriptDoc readScript(String content) {
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            return normalize(MAPPER.readValue(json, ScriptDoc.class));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 模型可能包代码围栏或前后带解释文字；取最外层花括号。 */
    private static String extractJsonObject(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String text = content.strip().replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    /**
     * 合同修复：场次号按位置定序、空场次丢弃、null 集合归零、空白人名剔除。
     * 模型输出是外部输入，落库前必须过这一道。
     */
    private static ScriptDoc normalize(ScriptDoc doc) {
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            return null;
        }
        List<ScriptDoc.Scene> kept = new ArrayList<>();
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            scene.setIndex(kept.size() + 1);
            scene.setCharacters(cleanNames(scene.getCharacters()));
            scene.setProps(cleanNames(scene.getProps()));
            List<ScriptDoc.Dialogue> dialogue = new ArrayList<>();
            for (ScriptDoc.Dialogue line : safe(scene.getDialogue())) {
                if (line == null || !StringUtils.hasText(line.getLine())) {
                    continue;
                }
                dialogue.add(line);
            }
            scene.setDialogue(dialogue);
            kept.add(scene);
        }
        if (kept.isEmpty()) {
            return null;
        }
        doc.setScenes(kept);
        return doc;
    }

    private static List<String> cleanNames(List<String> names) {
        List<String> clean = new ArrayList<>();
        for (String name : safe(names)) {
            if (StringUtils.hasText(name)) {
                clean.add(name.strip());
            }
        }
        return clean;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private ScriptDoc record(long seriesId, String engine, ScriptDoc doc) {
        PARSED_SERIES.set(seriesId);
        PARSED_ENGINE.set(engine);
        log.info("[drama-text] 分场完成 seriesId={} engine={} scenes={}",
                seriesId, engine, doc.getScenes().size());
        return doc;
    }
}
