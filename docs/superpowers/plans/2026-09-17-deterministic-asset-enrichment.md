# Deterministic Asset Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a complete deterministic drama-asset skeleton before LLM enrichment, degrade failed enrichment batches without failing the workflow, and expose a default-off reasoning switch for text models.

**Architecture:** `DramaAssetService` creates the style-aware source-of-truth list with `DramaAssetDrafts.fromScript`. `DramaTextServiceImpl` enriches that list in batches of four and merges only allowed descriptive fields into matching assets; failed batches retain their skeleton values. Text-model resolution, persistence, and the React model dialog all interpret a missing `enableThinking` setting as `false`.

**Tech Stack:** Java 17, Spring Boot 3.2, Jackson, JUnit 5, Mockito, React 19, Vite 6, Node's built-in test runner.

**Spec:** `docs/superpowers/specs/2026-09-17-deterministic-asset-enrichment-design.md`

## Global Constraints

- Asset membership, name, type, ordering, and persistence keys come only from the deterministic skeleton.
- LLM enrichment runs in fixed batches of four and may update only descriptive fields.
- A failed enrichment batch must retain its skeleton entries, keep `needsHuman=true`, and allow later batches to continue.
- Script parsing and storyboard drafting keep their existing strict failure behavior.
- Text-model reasoning defaults to disabled for new rows, existing rows without the property, and frontend drafts.
- Do not add a database migration or provider-specific reasoning protocols.
- Preserve unrelated changes in both `aigc-dit-be` and `aigc-dit-fe` worktrees.

---

### Task 1: Default Text-Model Reasoning to Disabled

**Files:**
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/drama/text/ResolvedTextModelTest.java`
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/drama/text/OpenAiCompatChatClientTest.java`
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/service/AdminModelServiceTest.java`
- Modify: `dit-server/src/main/java/cn/aigc/dit/server/drama/text/ResolvedTextModel.java`
- Modify: `dit-server/src/main/java/cn/aigc/dit/server/service/AdminModelService.java`

**Interfaces:**
- Consumes: `AiModel.adapterConfig`, `AdminModelSaveReq.adapterConfig`.
- Produces: every `ResolvedTextModel.adapterConfig()` and every newly saved text-model config contains boolean `enableThinking`; explicit `true` remains true.

- [ ] **Step 1: Write failing default and preservation tests**

Add these cases to `ResolvedTextModelTest`:

```java
@Test
void missingReasoningOptionDefaultsToDisabled() {
    ResolvedTextModel resolved = new ResolvedTextModel(
            11L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
            TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model", Map.of());

    assertEquals(false, resolved.adapterConfig().get("enableThinking"));
}

@Test
void explicitReasoningOptionIsPreserved() {
    ResolvedTextModel resolved = new ResolvedTextModel(
            11L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
            TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model",
            Map.of("enableThinking", true));

    assertEquals(true, resolved.adapterConfig().get("enableThinking"));
}
```

Extend `createTextModelUsesTextCatalogNotVideoAgnes` in `AdminModelServiceTest` to assert:

```java
assertEquals(false, model.getAdapterConfig().get("enableThinking"));
```

Add a second service test whose request uses `Map.of("enableThinking", true)` and whose mapper answer asserts that the stored value is `true`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./mvnw -q -pl dit-server -am \
  -Dtest=ResolvedTextModelTest,AdminModelServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failures because missing configuration currently produces an empty map and text-model save currently only injects `chatModel`.

- [ ] **Step 3: Implement the backend defaults**

Change the compact constructor in `ResolvedTextModel` to sanitize into a mutable `LinkedHashMap`, then apply the default before wrapping:

```java
Map<String, Object> configured = new LinkedHashMap<>();
if (adapterConfig != null) {
    adapterConfig.forEach((key, value) -> {
        if (key != null && value != null) {
            configured.put(key, value);
        }
    });
}
configured.putIfAbsent("enableThinking", false);
adapterConfig = Collections.unmodifiableMap(configured);
```

In `AdminModelService.applyText`, apply the persistence default before `chatModel`:

```java
adapterConfig.putIfAbsent("enableThinking", false);
adapterConfig.put("chatModel", modelKey);
```

- [ ] **Step 4: Add a request-shape regression test**

Add to `OpenAiCompatChatClientTest` a case that constructs a `ResolvedTextModel` with no explicit setting, passes its config into `client.chat`, captures the request JSON, and asserts:

```java
assertEquals(false,
        sent.path("chat_template_kwargs").path("enable_thinking").asBoolean());
```

This proves existing rows resolved with a missing property send the effective false default.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
./mvnw -q -pl dit-server -am \
  -Dtest=ResolvedTextModelTest,OpenAiCompatChatClientTest,AdminModelServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 6: Commit the backend reasoning default**

```bash
git add dit-server/src/main/java/cn/aigc/dit/server/drama/text/ResolvedTextModel.java \
  dit-server/src/main/java/cn/aigc/dit/server/service/AdminModelService.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/text/ResolvedTextModelTest.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/text/OpenAiCompatChatClientTest.java \
  dit-server/src/test/java/cn/aigc/dit/server/service/AdminModelServiceTest.java
git commit -m "feat: 默认关闭文本模型推理"
```

---

### Task 2: Add Batched Skeleton Enrichment

**Files:**
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextServiceBehaviorTest.java`
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextDraftPathsTest.java`
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextPromptsTest.java`
- Modify: `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextService.java`
- Modify: `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextServiceImpl.java`
- Modify: `dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextPrompts.java`

**Interfaces:**
- Consumes: `AssetDraftList skeleton` created by `DramaAssetDrafts.fromScript`.
- Produces: `AssetDraftList enrichAssets(long seriesId, ScriptDoc script, AssetDraftList skeleton)` with unchanged membership and order.
- Preserves: `extractAssets(long seriesId, ScriptDoc script)` for administrative/debug callers by creating a default-style skeleton and delegating.

- [ ] **Step 1: Write failing batching and merge tests**

Replace the old “asset contract never falls back” expectation in `DramaTextServiceBehaviorTest` with tests that exercise the desired public behavior:

```java
@Test
void failedAssetBatchRetainsSkeletonAndContinuesWithNextBatch() {
    AssetDraftList skeleton = skeleton("角色一", "角色二", "角色三", "角色四", "角色五");
    RecordingGateway gateway = new RecordingGateway(
            TextChatResult.of("", "portable-model", "length"),
            TextChatResult.of("", "portable-model", "length"),
            TextChatResult.of("""
                    {"assets":[{"name":"角色五","type":"角色",
                    "description":"模型描述","identity_invariants":"固定五官",
                    "image_prompt":"模型提示词","voice_lock":""}]}
                    """, "portable-model", "stop"));

    AssetDraftList result = new DramaTextServiceImpl(gateway)
            .enrichAssets(SERIES_ID, oneScene(), skeleton);

    assertEquals(5, result.getAssets().size());
    assertEquals("角色一", result.getAssets().get(0).getName());
    assertTrue(result.getAssets().get(0).getNeedsHuman());
    assertEquals("模型描述", result.getAssets().get(4).getDescription());
    assertEquals(false, result.getAssets().get(4).getNeedsHuman());
    assertEquals(3, gateway.users.size());
}
```

Add separate tests proving:

```java
assertEquals(originalNames, result.getAssets().stream().map(AssetDraft::getName).toList());
assertEquals(originalTypes, result.getAssets().stream().map(AssetDraft::getType).toList());
```

when the model adds an unknown asset, omits a supplied asset, or returns a renamed/retyped asset. Add a case where a returned description is blank and assert the non-blank skeleton description survives.

Add a local test helper that constructs skeleton entries with fallback values and `needsHuman=true`; do not add test-only behavior to production classes.

- [ ] **Step 2: Run the behavior test and verify RED**

Run:

```bash
./mvnw -q -pl dit-server -am \
  -Dtest=DramaTextServiceBehaviorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation failure because `enrichAssets` does not exist, which is the intended RED signal for the new public contract.

- [ ] **Step 3: Add the enrichment interface and prompt**

Add this default method to `DramaTextService`:

```java
default AssetDraftList enrichAssets(long seriesId, ScriptDoc script, AssetDraftList skeleton) {
    return skeleton;
}
```

Keep `extractAssets` compatible and delegate through the new method:

```java
default AssetDraftList extractAssets(long seriesId, ScriptDoc script) {
    return enrichAssets(seriesId, script,
            cn.aigc.dit.server.drama.DramaAssetDrafts.fromScript(script));
}
```

Add `DramaTextPrompts.assetEnrichmentUser(String scriptJson, String skeletonJson)` with concrete instructions that the response must contain exactly the supplied names and types and may only improve descriptive fields. Add assertions in `DramaTextPromptsTest` for “不得新增”, “不得删除”, “不得改名”, and both embedded JSON inputs.

- [ ] **Step 4: Implement batch orchestration and safe merging**

In `DramaTextServiceImpl`, add:

```java
private static final int ASSET_ENRICHMENT_BATCH_SIZE = 4;

@Override
public AssetDraftList extractAssets(long seriesId, ScriptDoc script) {
    return enrichAssets(seriesId, script, DramaAssetDrafts.fromScript(script));
}

@Override
public AssetDraftList enrichAssets(long seriesId, ScriptDoc script, AssetDraftList skeleton) {
    if (skeleton == null || skeleton.getAssets() == null || skeleton.getAssets().isEmpty()) {
        return skeleton;
    }
    try {
        if (!useLlm(seriesId)) {
            return skeleton;
        }
    } catch (RuntimeException e) {
        log.warn("[drama-text] 资产增强不可用，保留骨架 seriesId={} error={}",
                seriesId, e.getMessage());
        return skeleton;
    }
    List<AssetDraftList.AssetDraft> assets = skeleton.getAssets();
    String scriptJson = toJson(script);
    for (int start = 0; start < assets.size(); start += ASSET_ENRICHMENT_BATCH_SIZE) {
        int end = Math.min(start + ASSET_ENRICHMENT_BATCH_SIZE, assets.size());
        AssetDraftList batch = new AssetDraftList();
        batch.setAssets(new ArrayList<>(assets.subList(start, end)));
        try {
            AssetDraftList enriched = attemptJson(
                    seriesId,
                    DramaTextPrompts.assetSystem(),
                    DramaTextPrompts.assetEnrichmentUser(scriptJson, toJson(batch)),
                    content -> normalizeAssets(readList(content, AssetDraftList.class)),
                    "AssetDraftList",
                    4096);
            mergeAssetEnrichment(assets.subList(start, end), enriched);
        } catch (RuntimeException e) {
            log.warn("[drama-text] 资产增强批次失败，保留骨架 seriesId={} batch={} size={} error={}",
                    seriesId, start / ASSET_ENRICHMENT_BATCH_SIZE + 1, end - start, e.getMessage());
        }
    }
    return skeleton;
}
```

Use a package-private static merge helper so tests can exercise identity protection directly:

```java
static void mergeAssetEnrichment(
        List<AssetDraftList.AssetDraft> skeleton,
        AssetDraftList enrichment) {
    Map<String, AssetDraftList.AssetDraft> candidates = enrichment.getAssets().stream()
            .filter(Objects::nonNull)
            .filter(item -> StringUtils.hasText(item.getName()) && StringUtils.hasText(item.getType()))
            .collect(Collectors.toMap(
                    item -> assetIdentity(item.getType(), item.getName()),
                    item -> item,
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    for (AssetDraftList.AssetDraft target : skeleton) {
        AssetDraftList.AssetDraft source = candidates.get(assetIdentity(target.getType(), target.getName()));
        if (source == null) {
            continue;
        }
        copyNonBlankEnrichment(target, source);
        target.setNeedsHuman(!(StringUtils.hasText(source.getDescription())
                && StringUtils.hasText(source.getIdentityInvariants())
                && StringUtils.hasText(source.getImagePrompt())));
    }
}
```

`copyNonBlankEnrichment` copies only `description`, `identityInvariants`, `imagePrompt`, and `voiceLock`. It never copies `name`, `type`, `stateTag`, or `mustSplit`.

Implement the identity and non-blank copy helpers as:

```java
private static String assetIdentity(String type, String name) {
    return type.strip() + "\0" + name.strip();
}

private static void copyNonBlankEnrichment(
        AssetDraftList.AssetDraft target,
        AssetDraftList.AssetDraft source) {
    if (StringUtils.hasText(source.getDescription())) {
        target.setDescription(source.getDescription().strip());
    }
    if (StringUtils.hasText(source.getIdentityInvariants())) {
        target.setIdentityInvariants(source.getIdentityInvariants().strip());
    }
    if (StringUtils.hasText(source.getImagePrompt())) {
        target.setImagePrompt(source.getImagePrompt().strip());
    }
    if (StringUtils.hasText(source.getVoiceLock())) {
        target.setVoiceLock(source.getVoiceLock().strip());
    }
}
```

Catch model-resolution failure around `useLlm(seriesId)` and batch failures around `attemptJson`. Log only structural metadata and exception text, never prompt or returned content.

- [ ] **Step 5: Update legacy asset-path expectations**

Change `DramaTextDraftPathsTest` asset cases so:

- an unbound series returns the deterministic skeleton and never calls `gateway.chat`;
- an empty model list returns the supplied skeleton instead of throwing;
- a transport failure returns the supplied skeleton instead of surfacing;
- model-only invented assets are no longer expected in the final list;
- dream-state assertions use dream-state entries already present in the skeleton.

Keep all shot tests unchanged.

- [ ] **Step 6: Run text-service tests and verify GREEN**

Run:

```bash
./mvnw -q -pl dit-server -am \
  -Dtest=DramaTextServiceBehaviorTest,DramaTextDraftPathsTest,DramaTextPromptsTest,DramaTextServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS, including exactly two attempts for a failed batch and continued processing of later batches.

- [ ] **Step 7: Commit batched enrichment**

```bash
git add dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextService.java \
  dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextServiceImpl.java \
  dit-server/src/main/java/cn/aigc/dit/server/drama/text/DramaTextPrompts.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextServiceBehaviorTest.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextDraftPathsTest.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextPromptsTest.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/text/DramaTextServiceImplTest.java
git commit -m "feat: 分批增强确定性资产骨架"
```

---

### Task 3: Make Drama Asset Extraction Always Start from the Style-Aware Skeleton

**Files:**
- Modify: `dit-server/src/test/java/cn/aigc/dit/server/drama/DramaAssetServiceTest.java`
- Modify: `dit-server/src/main/java/cn/aigc/dit/server/drama/DramaAssetService.java`

**Interfaces:**
- Consumes: `DramaTextService.enrichAssets(seriesId, doc, skeleton)` from Task 2.
- Produces: the production extraction path always persists the complete style-aware skeleton, enriched when possible.

- [ ] **Step 1: Replace the missing-service failure test with a fallback test**

Rewrite `llmAssetExtractionDoesNotFallBackWhenTextServiceIsMissing` as:

```java
@Test
void llmAssetExtractionUsesSkeletonWhenTextServiceIsMissing() {
    DramaEpisode episode = ownedEpisode();
    episode.setStatus(DramaContract.STATUS_LOCKED);
    episode.setScript(ScriptDocs.toMap(sampleDoc()));
    when(episodeMapper.selectById(31L)).thenReturn(episode);
    when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
    when(assetMapper.selectList(any())).thenReturn(List.of());
    when(assetMapper.insert(any(DramaAsset.class))).thenReturn(1);

    service.extractFromScript(31L, true);

    verify(assetMapper, org.mockito.Mockito.atLeast(4)).insert(any(DramaAsset.class));
}
```

Add a second test with a mocked `DramaTextService` that captures the third argument passed to `enrichAssets` and asserts its character prompt contains the owned series style (`二次元漫画风`). Return that same skeleton and verify it is persisted.

- [ ] **Step 2: Run the service test and verify RED**

Run:

```bash
./mvnw -q -pl dit-server -am \
  -Dtest=DramaAssetServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the old implementation throws `剧本生成服务尚未就绪` and never calls the new enrichment method.

- [ ] **Step 3: Change the orchestration**

Replace the current LLM/rules branch with this flow:

```java
AssetDraftList drafts = DramaAssetDrafts.fromScript(doc, style);
DramaTextService svc = textService.getIfAvailable();
if (useLlm && svc != null) {
    drafts = svc.enrichAssets(episode.getSeriesId(), doc, drafts);
} else if (useLlm) {
    log.warn("[drama] 文本服务不可用，保留确定性资产骨架 episodeId={}", episodeId);
}
DramaAssetDrafts.ensurePrompts(drafts, doc, style);
```

Keep the existing final empty-list guard and persistence loop.

- [ ] **Step 4: Run the asset service tests and verify GREEN**

Run:

```bash
./mvnw -q -pl dit-server -am \
  -Dtest=DramaAssetServiceTest,DramaServiceScriptTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. `DramaServiceScriptTest` remains strict about the text service for script parsing; only asset extraction changes fallback policy.

- [ ] **Step 5: Commit production orchestration**

```bash
git add dit-server/src/main/java/cn/aigc/dit/server/drama/DramaAssetService.java \
  dit-server/src/test/java/cn/aigc/dit/server/drama/DramaAssetServiceTest.java
git commit -m "feat: 资产提取优先生成确定性骨架"
```

---

### Task 4: Add the Text-Model Reasoning Switch to Model Management

**Files:**
- Create: `../aigc-dit-fe/src/components/adminModelConfig.js`
- Create: `../aigc-dit-fe/tests/admin-model-config.test.mjs`
- Modify: `../aigc-dit-fe/src/components/AdminConsole.jsx`
- Modify: `../aigc-dit-fe/package.json`

**Interfaces:**
- Produces: `textReasoningEnabled(adapterConfig): boolean` and `withTextReasoning(adapterConfig, enabled): object`.
- Consumes: these pure helpers from `AdminConsole.jsx` for initialization, category switching, toggle updates, and payload serialization.

- [ ] **Step 1: Add the frontend test command and failing helper tests**

Add to `package.json`:

```json
"test": "node --test tests/*.test.mjs"
```

Create `tests/admin-model-config.test.mjs`:

```javascript
import test from "node:test";
import assert from "node:assert/strict";
import {
  textReasoningEnabled,
  withTextReasoning,
} from "../src/components/adminModelConfig.js";

test("missing text reasoning setting defaults to disabled", () => {
  assert.equal(textReasoningEnabled(undefined), false);
  assert.equal(textReasoningEnabled({ chatModel: "qwen" }), false);
});

test("explicit text reasoning setting is preserved", () => {
  assert.equal(textReasoningEnabled({ enableThinking: true }), true);
  assert.equal(textReasoningEnabled({ enableThinking: false }), false);
});

test("setting text reasoning preserves other adapter options", () => {
  assert.deepEqual(
    withTextReasoning({ chatModel: "qwen", temperature: 0.1 }, true),
    { chatModel: "qwen", temperature: 0.1, enableThinking: true },
  );
});
```

- [ ] **Step 2: Run frontend tests and verify RED**

Run from `aigc-dit-fe`:

```bash
npm test
```

Expected: failure because `src/components/adminModelConfig.js` does not exist.

- [ ] **Step 3: Implement the pure configuration helpers**

Create `src/components/adminModelConfig.js`:

```javascript
export function textReasoningEnabled(adapterConfig) {
  return adapterConfig?.enableThinking === true;
}

export function withTextReasoning(adapterConfig, enabled = false) {
  return {
    ...(adapterConfig || {}),
    enableThinking: enabled === true,
  };
}
```

- [ ] **Step 4: Run frontend tests and verify GREEN**

Run:

```bash
npm test
```

Expected: all three tests PASS.

- [ ] **Step 5: Wire the switch into `ModelDialog`**

Import the helpers in `AdminConsole.jsx`. For existing model initialization, normalize the text model config:

```javascript
adapterConfig: model.category === "text"
  ? withTextReasoning(model.adapterConfig, textReasoningEnabled(model.adapterConfig))
  : (model.adapterConfig || {}),
```

When `applyAdapter` changes the category to `text`, ensure a missing setting becomes false without deleting other settings:

```javascript
if (category === "text") {
  next.adapterConfig = withTextReasoning(
    current.adapterConfig,
    textReasoningEnabled(current.adapterConfig),
  );
}
```

Render this text-only control after the model identifier/base URL fields:

```jsx
{isText && <label className="model-form-wide model-toggle-field">
  <span>启用推理</span>
  <input
    type="checkbox"
    checked={textReasoningEnabled(draft.adapterConfig)}
    onChange={(event) => setDraft((current) => ({
      ...current,
      adapterConfig: withTextReasoning(current.adapterConfig, event.target.checked),
    }))}
  />
  <small className="model-logo-hint">结构化剧本分析建议关闭，避免推理内容占满输出额度。</small>
</label>}
```

In `toModelPayload`, always serialize a boolean for text models:

```javascript
if (category === "text") {
  adapterConfig.chatModel = draft.modelKey;
  adapterConfig.enableThinking = textReasoningEnabled(draft.adapterConfig);
}
```

- [ ] **Step 6: Run frontend verification**

Run from `aigc-dit-fe`:

```bash
npm test
npm run lint
npm run build
```

Expected: all commands exit 0; the Vite build produces `dist` without JSX or import errors.

- [ ] **Step 7: Verify the visible interaction in a browser**

Start the existing frontend preview flow and inspect Model Management:

- create a text-model draft and confirm `启用推理` is unchecked;
- edit a model whose response contains `enableThinking=true` and confirm it is checked;
- toggle it, save, reopen, and confirm the saved state is shown;
- switch to a non-text category and confirm the control is hidden.

- [ ] **Step 8: Commit the frontend switch**

Run from `aigc-dit-fe`:

```bash
git add package.json src/components/AdminConsole.jsx \
  src/components/adminModelConfig.js tests/admin-model-config.test.mjs
git commit -m "feat: 增加文本模型推理开关"
```

---

### Task 5: Full Regression Verification

**Files:**
- Verify only; modify production files only if a failing regression directly contradicts the approved specification.

**Interfaces:**
- Consumes: all backend and frontend behavior from Tasks 1–4.
- Produces: evidence that both repositories build and their relevant test suites pass.

- [ ] **Step 1: Run the complete backend module test suite**

Run from `aigc-dit-be`:

```bash
./mvnw -q -pl dit-server -am test
```

Expected: PASS with no test failures.

- [ ] **Step 2: Run complete frontend checks**

Run from `aigc-dit-fe`:

```bash
npm test
npm run lint
npm run build
```

Expected: all commands exit 0.

- [ ] **Step 3: Inspect diffs and repository state**

Run in each repository:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and no uncommitted task files. Do not alter unrelated user-owned changes if any appear.

- [ ] **Step 4: Report behavioral results**

Report:

- deterministic skeleton count and order are preserved across enrichment failures;
- successful batches improve descriptive fields while failed batches remain `needsHuman=true`;
- empty-content/length failures no longer abort asset extraction;
- text-model reasoning is off by default and explicit enablement reaches the request body;
- backend tests, frontend tests, lint, build, and browser interaction results.
