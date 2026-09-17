# Deterministic Asset Enrichment Design

**Date:** 2026-09-17

## 1. Goal

Make drama asset extraction resilient across text models with different structured-output capabilities. Asset names and types must always come from the locked `ScriptDoc`; the LLM may enrich those assets but must not be able to remove, add, rename, or retype them. A failed enrichment batch must not fail the complete subject-extraction workflow.

Expose a per-text-model reasoning switch in Model Management. New models and existing models without an explicit setting default to reasoning disabled.

## 2. Scope

This change covers:

- deterministic asset skeleton generation from `ScriptDoc`;
- batched LLM enrichment of the deterministic skeleton;
- per-batch validation, safe merging, and graceful fallback;
- model-management UI and backend defaults for `adapterConfig.enableThinking`;
- tests for the new backend behavior and frontend configuration mapping.

This change does not alter:

- LLM-based script parsing;
- storyboard drafting or its strict contract failures;
- asset persistence keys or database schema;
- the provider-specific representation currently used to disable reasoning (`chat_template_kwargs.enable_thinking`).

## 3. Current Problem

`DramaTextServiceImpl.extractAssets` currently asks the model to produce the complete `AssetDraftList` in one response with a 4096-token limit. Reasoning-capable models can consume the whole budget before producing `message.content`, and a large asset list can also exceed the response budget. The retry path sends another full request with the same model and token limit, so `finish_reason=length` and empty content commonly repeat.

The project already has deterministic asset generation in `DramaAssetDrafts.fromScript`, but the LLM path bypasses it and treats an invalid model response as fatal.

## 4. Selected Architecture

### 4.1 Deterministic source of truth

`DramaAssetService.extractFromScript` creates the asset skeleton first with:

```java
DramaAssetDrafts.fromScript(doc, style)
```

The skeleton derives:

- characters from `scene.characters`;
- props from `scene.props`;
- scenes from `scene.location`;
- state tags from the existing state-tag inference;
- stable insertion order and de-duplication from the existing key rules;
- style-aware fallback descriptions and image prompts from the current templates.

The skeleton is the authoritative collection. Model output can never change its membership or identity fields.

### 4.2 Batched enrichment

When `useLlm=true` and a usable text model is bound, the text service enriches the skeleton in batches of four assets. Each request contains:

- the relevant locked `ScriptDoc` context;
- exactly four or fewer skeleton items;
- a strict instruction to return each supplied item once;
- the existing `AssetDraftList` JSON contract;
- an explicit prohibition on adding, removing, renaming, or retyping assets.

The batch size is a fixed internal constant for this version. Four keeps the output comfortably below the existing 4096-token asset budget while avoiding excessive request count. No administrator-facing batch-size setting is added.

### 4.3 Safe merge

Returned items are matched to skeleton items by normalized `type + name`. Unknown items are ignored. Missing items leave their skeleton entries unchanged.

Only these fields may be copied from a valid model result:

- `description`;
- `identityInvariants`;
- `imagePrompt`;
- `voiceLock`;
- validated `stateTag` and `mustSplit` values when they do not change the asset name or type.

Blank model fields never overwrite non-blank skeleton fields. A successfully enriched item is marked `needsHuman=false` only when `description`, `identityInvariants`, and `imagePrompt` are all non-blank. Otherwise it remains `needsHuman=true`.

The final list preserves the skeleton's original order and size.

### 4.4 Failure behavior

Each batch keeps the existing two-attempt JSON contract retry. If a batch still fails because of empty content, truncation, invalid JSON, timeout, transport error, or invalid contract:

- the exception is caught at the batch boundary;
- the affected skeleton entries are retained unchanged;
- their `needsHuman` flag remains true;
- a warning records series ID, batch index, batch size, model failure reason, and elapsed time without logging script or model content;
- remaining batches continue.

If no text model is bound or the text service is unavailable, the workflow returns the deterministic skeleton without calling the model. Model-binding failures therefore no longer block asset extraction. Script parsing and shot drafting retain their existing strict behavior.

### 4.5 Service boundary

`DramaAssetService` owns skeleton creation because it already has the project style required by `DramaAssetDrafts.fromScript(doc, style)`. `DramaTextService` receives the skeleton and returns an enriched copy/list. This avoids creating anime-default prompts inside the text service for projects using another style.

The existing direct `extractAssets(seriesId, script)` entry point remains compatible for administrative/debug callers by creating a default-style skeleton and delegating to the enrichment path. The production `DramaAssetService` path always supplies the style-aware skeleton.

## 5. Reasoning Switch

### 5.1 Backend contract

For every saved text model, `AdminModelService` ensures:

```json
{
  "enableThinking": false
}
```

when the request omits the property. An explicit `true` is preserved. Non-text model configuration is unchanged. Resolution of an existing text-model row that lacks the property also supplies the same false default, so the runtime behavior changes immediately without waiting for that model to be edited and saved.

`OpenAiCompatChatClient` continues mapping this property to:

```json
{
  "chat_template_kwargs": {
    "enable_thinking": false
  }
}
```

for JSON-mode requests. Provider-specific reasoning protocols remain outside this change.

### 5.2 Frontend behavior

The Model Management dialog displays an `启用推理` switch for text models only.

- New text model: off.
- Existing text model with `enableThinking=true`: on.
- Existing text model with the property absent: off.
- Saving always writes a boolean to `adapterConfig.enableThinking`.
- Switching a draft from another category to text initializes the missing property to false without deleting other adapter configuration.

Helper copy explains that structured script analysis is more stable with reasoning disabled.

## 6. Data Flow

```text
Locked ScriptDoc + Project Style
            |
            v
DramaAssetDrafts.fromScript(doc, style)
            |
            +---- no model / service unavailable -------------------+
            |                                                        |
            v                                                        |
Split skeleton into batches of 4                                    |
            |                                                        |
            v                                                        |
LLM returns enrichment-only AssetDraftList                           |
            |                                                        |
            v                                                        |
Validate and merge allowed fields by type + name                     |
            |                                                        |
            +---- failed batch -> retain that skeleton batch --------+
            |
            v
Persist complete ordered asset list
```

## 7. Validation and Observability

Tests must prove that:

- deterministic skeleton assets survive an empty or invalid LLM response;
- one failed batch does not prevent later batches from being enriched;
- model-added assets are ignored;
- model-omitted assets remain present;
- model attempts to rename or retype assets do not affect identity;
- blank enrichment fields do not erase fallback values;
- output order matches the skeleton order;
- calls split at four assets per batch;
- no bound model returns the skeleton without a gateway chat call;
- text-model save defaults `enableThinking` to false;
- explicit `enableThinking=true` is preserved;
- the frontend renders the text-only switch and serializes both states.

Existing script and shot contract tests remain unchanged and must continue passing.

## 8. Compatibility and Rollout

No migration is required because `ai_model.adapter_config` is already JSONB. Existing rows without `enableThinking` are interpreted as false by both model resolution and the UI, and receive an explicit false value the next time they are saved. A separate data migration is intentionally avoided.

Asset extraction becomes more available: callers that previously received a model-contract error may now receive deterministic assets marked for human review. API response shapes and persistence tables remain unchanged.

## 9. Non-Goals

- Automatically discovering provider-specific reasoning-control fields.
- Dynamically tuning batch size or token budgets.
- Replacing deterministic state-tag keywords with a new state ontology.
- Changing script parsing or storyboard generation fallback policy.
- Adding database columns for enrichment status.
