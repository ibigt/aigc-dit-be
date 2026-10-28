package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.drama.model.req.DramaAssetGenerateReq;
import cn.aigc.dit.server.drama.model.req.DramaAssetSaveReq;
import cn.aigc.dit.server.drama.model.req.DramaEpisodeUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaFilmAssembleReq;
import cn.aigc.dit.server.drama.model.req.DramaImageModelBindReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptImportReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptAnalyzeReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptSaveReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaShotBatchGenerateReq;
import cn.aigc.dit.server.drama.model.req.DramaShotCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaShotFrameAssetReq;
import cn.aigc.dit.server.drama.model.req.DramaShotSaveReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaVideoModelBindReq;
import cn.aigc.dit.server.drama.model.req.DramaTextModelBindReq;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerationResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.model.resp.DramaArkReviewAvailabilityResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetReviewResp;
import cn.aigc.dit.server.drama.model.resp.DramaEpisodeResp;
import cn.aigc.dit.server.drama.model.resp.DramaFilmResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotBatchGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerationResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotResp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.model.resp.DramaImageModelResp;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.drama.model.resp.DramaStyleResp;
import cn.aigc.dit.server.drama.model.resp.DramaTextModelResp;
import cn.aigc.dit.server.drama.model.resp.DramaVideoModelResp;
import cn.aigc.dit.server.drama.model.resp.DramaScriptAnalyzeResp;
import cn.aigc.dit.server.service.ArkReviewCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "漫剧")
@RestController
@RequestMapping("/api/drama")
public class DramaController {

    private final DramaService dramaService;
    private final DramaAssetService dramaAssetService;
    private final DramaStoryboardService dramaStoryboardService;
    private final DramaShotBatchGenerateService dramaShotBatchGenerateService;
    private final DramaFilmService dramaFilmService;
    private final DramaCaptionService dramaCaptionService;
    private final ArkReviewCredentialService arkReviewCredentialService;

    public DramaController(DramaService dramaService,
                           DramaAssetService dramaAssetService,
                           DramaStoryboardService dramaStoryboardService,
                           DramaShotBatchGenerateService dramaShotBatchGenerateService,
                           DramaFilmService dramaFilmService,
                           DramaCaptionService dramaCaptionService,
                           ArkReviewCredentialService arkReviewCredentialService) {
        this.dramaService = dramaService;
        this.dramaAssetService = dramaAssetService;
        this.dramaStoryboardService = dramaStoryboardService;
        this.dramaShotBatchGenerateService = dramaShotBatchGenerateService;
        this.dramaFilmService = dramaFilmService;
        this.dramaCaptionService = dramaCaptionService;
        this.arkReviewCredentialService = arkReviewCredentialService;
    }

    @Operation(summary = "可选视觉风格（图例；不含编译前缀）")
    @GetMapping("/styles")
    public CommonResult<List<DramaStyleResp>> listStyles() {
        return CommonResult.success(dramaService.listStyles());
    }

    @Operation(summary = "已启用文本模型（新建/更换绑定；不含密钥）")
    @GetMapping("/text-models")
    public CommonResult<List<DramaTextModelResp>> listTextModels() {
        return CommonResult.success(dramaService.listEnabledTextModels());
    }

    @Operation(summary = "已启用图像模型（资产页全局绑定；不含密钥）")
    @GetMapping("/image-models")
    public CommonResult<List<DramaImageModelResp>> listImageModels() {
        return CommonResult.success(dramaService.listEnabledImageModels());
    }

    @Operation(summary = "已启用视频模型（分镜页全局绑定；仅 r2v；不含密钥）")
    @GetMapping("/video-models")
    public CommonResult<List<DramaVideoModelResp>> listVideoModels() {
        return CommonResult.success(dramaService.listEnabledVideoModels());
    }

    @Operation(summary = "系列列表")
    @GetMapping("/series")
    public CommonResult<List<DramaSeriesResp>> listSeries() {
        return CommonResult.success(dramaService.listSeries());
    }

    @Operation(summary = "系列详情（含剧集）")
    @GetMapping("/series/{id}")
    public CommonResult<DramaSeriesResp> getSeries(@PathVariable Long id) {
        return CommonResult.success(dramaService.getSeries(id));
    }

    @Operation(summary = "新建系列并创建第 1 集")
    @PostMapping("/series")
    public CommonResult<DramaSeriesResp> createSeries(@Valid @RequestBody DramaSeriesCreateReq req) {
        return CommonResult.success(dramaService.createSeries(req));
    }

    @Operation(summary = "更新系列基础信息")
    @PutMapping("/series/{id}")
    public CommonResult<DramaSeriesResp> updateSeries(
            @PathVariable Long id,
            @Valid @RequestBody DramaSeriesUpdateReq req) {
        return CommonResult.success(dramaService.updateSeries(id, req));
    }

    @Operation(summary = "章节列表添加一集，返回含全部剧集的项目详情")
    @PostMapping("/series/{id}/episodes")
    public CommonResult<DramaSeriesResp> createEpisode(@PathVariable Long id) {
        return CommonResult.success(dramaService.createEpisode(id));
    }

    @Operation(summary = "删除系列（级联剧集、资产、章节、分镜）")
    @DeleteMapping("/series/{id}")
    public CommonResult<Void> deleteSeries(@PathVariable Long id) {
        dramaService.deleteSeries(id);
        return CommonResult.success();
    }

    @Operation(summary = "剧集详情")
    @GetMapping("/episodes/{id}")
    public CommonResult<DramaEpisodeResp> getEpisode(@PathVariable Long id) {
        return CommonResult.success(dramaService.getEpisode(id));
    }

    @Operation(summary = "更新剧集名称")
    @PutMapping("/episodes/{id}")
    public CommonResult<DramaEpisodeResp> updateEpisode(
            @PathVariable Long id,
            @Valid @RequestBody DramaEpisodeUpdateReq req) {
        return CommonResult.success(dramaService.updateEpisode(id, req));
    }

    @Operation(summary = "删除一集（级联资产/章节/分镜/成片），至少保留一集；返回更新后的项目详情")
    @DeleteMapping("/episodes/{id}")
    public CommonResult<DramaSeriesResp> deleteEpisode(@PathVariable Long id) {
        return CommonResult.success(dramaService.deleteEpisode(id));
    }

    @Operation(summary = "导入剧本原文；乙未合入时只存 source_text")
    @PostMapping("/episodes/{id}/script/import")
    public CommonResult<DramaEpisodeResp> importSource(
            @PathVariable Long id,
            @Valid @RequestBody DramaScriptImportReq req) {
        return CommonResult.success(dramaService.importSource(id, req));
    }

    @Operation(summary = "从梗概生成分场剧本（需乙实现且项目文本模型可用）")
    @PostMapping("/episodes/{id}/script/generate")
    public CommonResult<DramaEpisodeResp> generateScript(
            @PathVariable Long id,
            @Valid @RequestBody DramaScriptImportReq req) {
        return CommonResult.success(dramaService.generateScript(id, req));
    }

    @Operation(summary = "一键分析剧本：分场、抽取资产并生成分镜草稿")
    @PostMapping("/episodes/{id}/script/analyze")
    public CommonResult<DramaScriptAnalyzeResp> analyzeScript(
            @PathVariable Long id,
            @Valid @RequestBody DramaScriptAnalyzeReq req) {
        return CommonResult.success(dramaService.analyzeScript(id, req));
    }

    @Operation(summary = "保存分场剧本；已锁定后再改会使本集回到待确认、下游过期")
    @PutMapping("/episodes/{id}/script")
    public CommonResult<DramaEpisodeResp> saveScript(
            @PathVariable Long id,
            @Valid @RequestBody DramaScriptSaveReq req) {
        return CommonResult.success(dramaService.saveScript(id, req));
    }

    @Operation(summary = "锁定分场剧本")
    @PostMapping("/episodes/{id}/script/lock")
    public CommonResult<DramaEpisodeResp> lockScript(@PathVariable Long id) {
        return CommonResult.success(dramaService.lockScript(id));
    }

    @Operation(summary = "资产页全局绑定图像模型（本页所有 t2i 都用这一条）")
    @PutMapping("/series/{id}/image-model")
    public CommonResult<DramaSeriesResp> bindImageModel(
            @PathVariable Long id,
            @Valid @RequestBody DramaImageModelBindReq req) {
        return CommonResult.success(dramaService.bindImageModel(id, req));
    }

    @Operation(summary = "分镜页全局绑定视频模型（本页所有出片都用这一条）")
    @PutMapping("/series/{id}/video-model")
    public CommonResult<DramaSeriesResp> bindVideoModel(
            @PathVariable Long id,
            @Valid @RequestBody DramaVideoModelBindReq req) {
        return CommonResult.success(dramaService.bindVideoModel(id, req));
    }

    @Operation(summary = "剧本页绑定文本模型；传 null 解绑，解析与拆镜将不可用")
    @PutMapping("/series/{id}/text-model")
    public CommonResult<DramaSeriesResp> bindTextModel(
            @PathVariable Long id,
            @RequestBody DramaTextModelBindReq req) {
        return CommonResult.success(dramaService.bindTextModel(id, req));
    }

    @Operation(summary = "剧集资产列表")
    @GetMapping("/episodes/{id}/assets")
    public CommonResult<List<DramaAssetResp>> listAssets(@PathVariable Long id) {
        return CommonResult.success(dramaAssetService.listByEpisode(id));
    }

    @Operation(summary = "从锁定剧本提取角色/场景/道具")
    @PostMapping("/episodes/{id}/assets/extract")
    public CommonResult<List<DramaAssetResp>> extractAssets(@PathVariable Long id) {
        return CommonResult.success(dramaAssetService.extractFromScript(id));
    }

    @Operation(summary = "手工新增资产（角色仅建基础版）")
    @PostMapping("/episodes/{id}/assets")
    public CommonResult<DramaAssetResp> createAsset(
            @PathVariable Long id,
            @Valid @RequestBody DramaAssetSaveReq req) {
        return CommonResult.success(dramaAssetService.create(id, req));
    }

    @Operation(summary = "基于某版本延伸新建角色版本")
    @PostMapping("/assets/{id}/versions")
    public CommonResult<DramaAssetResp> createAssetVersion(
            @PathVariable Long id,
            @Valid @RequestBody cn.aigc.dit.server.drama.model.req.DramaAssetVersionReq req) {
        return CommonResult.success(dramaAssetService.createVersion(id, req));
    }

    @Operation(summary = "更新资产描述；已锁定后再改会回到待确认")
    @PutMapping("/assets/{id}")
    public CommonResult<DramaAssetResp> updateAsset(
            @PathVariable Long id,
            @Valid @RequestBody DramaAssetSaveReq req) {
        return CommonResult.success(dramaAssetService.update(id, req));
    }

    @Operation(summary = "删除资产")
    @DeleteMapping("/assets/{id}")
    public CommonResult<Void> deleteAsset(@PathVariable Long id) {
        dramaAssetService.delete(id);
        return CommonResult.success();
    }

    @Operation(summary = "上传参考图（不依赖图像模型）")
    @PostMapping("/assets/{id}/upload")
    public CommonResult<DramaAssetResp> uploadAssetImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return CommonResult.success(dramaAssetService.uploadImage(id, file));
    }

    @Operation(summary = "从图片素材库引用为资产参考图")
    @PostMapping("/assets/{id}/upload/library/{libraryAssetId}")
    public CommonResult<DramaAssetResp> bindAssetImageFromLibrary(
            @PathVariable Long id,
            @PathVariable Long libraryAssetId) {
        return CommonResult.success(dramaAssetService.bindImageFromLibrary(id, libraryAssetId));
    }

    @Operation(summary = "上传角色音色参考（用于支持 Audio 引用的原生有声视频模型）")
    @PostMapping("/assets/{id}/voice-reference")
    public CommonResult<DramaAssetResp> uploadAssetVoiceReference(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return CommonResult.success(dramaAssetService.uploadVoiceReference(id, file));
    }

    @Operation(summary = "从音频素材库绑定角色音色参考")
    @PostMapping("/assets/{id}/voice-reference/library/{libraryAssetId}")
    public CommonResult<DramaAssetResp> bindAssetVoiceReference(
            @PathVariable Long id,
            @PathVariable Long libraryAssetId) {
        return CommonResult.success(dramaAssetService.bindVoiceReference(id, libraryAssetId));
    }

    @Operation(summary = "用系列绑定的图像模型纯 t2i；不按素材选模型")
    @PostMapping("/assets/{id}/generate")
    public CommonResult<DramaAssetGenerateResp> generateAssetImage(
            @PathVariable Long id,
            @RequestBody(required = false) DramaAssetGenerateReq req) {
        return CommonResult.success(dramaAssetService.generateImage(
                id, req == null ? null : req.getResolution()));
    }

    @Operation(summary = "外部图经图生文转 Seedream 5.0 资产图（纯 T2I，写 origin）")
    @PostMapping("/assets/{id}/convert-to-seedream")
    public CommonResult<DramaAssetGenerateResp> convertAssetToSeedream(
            @PathVariable Long id,
            @RequestBody(required = false) DramaAssetGenerateReq req) {
        return CommonResult.success(dramaAssetService.convertToSeedream(
                id, req == null ? null : req.getResolution()));
    }

    @Operation(summary = "查询资产生图任务；成功后写入参考图")
    @GetMapping("/assets/{id}/generate/tasks/{taskId}")
    public CommonResult<DramaAssetGenerateResp> getAssetGenerateTask(
            @PathVariable Long id,
            @PathVariable Long taskId) {
        return CommonResult.success(dramaAssetService.getGenerateTask(id, taskId));
    }

    @Operation(summary = "资产生成记录列表（新→旧）")
    @GetMapping("/assets/{id}/generations")
    public CommonResult<List<DramaAssetGenerationResp>> listAssetGenerations(@PathVariable Long id) {
        return CommonResult.success(dramaAssetService.listGenerations(id));
    }

    @Operation(summary = "选用一条资产生成记录为最终；随时可切换，分镜使用当前图")
    @PostMapping("/assets/{id}/generations/{gid}/select")
    public CommonResult<DramaAssetResp> selectAssetGeneration(
            @PathVariable Long id,
            @PathVariable Long gid) {
        return CommonResult.success(dramaAssetService.selectGeneration(id, gid));
    }

    @Operation(summary = "删除资产生成记录；删选用记录会自动切换到剩余图")
    @DeleteMapping("/assets/{id}/generations/{gid}")
    public CommonResult<Void> deleteAssetGeneration(
            @PathVariable Long id,
            @PathVariable Long gid) {
        dramaAssetService.deleteGeneration(id, gid);
        return CommonResult.success();
    }

    @Operation(summary = "角色审核密钥是否已配好。未配好时前端隐藏审核按钮，不回传密钥")
    @GetMapping("/ark-review")
    public CommonResult<DramaArkReviewAvailabilityResp> arkReviewAvailability() {
        DramaArkReviewAvailabilityResp resp = new DramaArkReviewAvailabilityResp();
        resp.setConfigured(arkReviewCredentialService.reviewEnabled());
        return CommonResult.success(resp);
    }

    @Operation(summary = "把当前选用的角色图送入方舟素材库，轮询到 Active 或 Failed")
    @PostMapping("/assets/{id}/ark-review")
    public CommonResult<DramaAssetReviewResp> reviewCharacterAsset(@PathVariable Long id) {
        return CommonResult.success(dramaAssetService.reviewCharacter(id));
    }

    @Operation(summary = "将资产生成记录导入素材库")
    @PostMapping("/assets/{id}/generations/{gid}/import-library")
    public CommonResult<DramaAssetGenerationResp> importAssetGeneration(
            @PathVariable Long id,
            @PathVariable Long gid) {
        return CommonResult.success(dramaAssetService.importGenerationToLibrary(id, gid));
    }

    @Operation(summary = "章节与分镜（含编译预览）")
    @GetMapping("/episodes/{id}/storyboard")
    public CommonResult<DramaStoryboardResp> getStoryboard(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.getStoryboard(id));
    }

    @Operation(summary = "按场拆章并生成分镜草稿，随后编译 Style Prefix / 身份 / Picture")
    @PostMapping("/episodes/{id}/storyboard/draft")
    public CommonResult<DramaStoryboardResp> draftStoryboard(
            @PathVariable Long id,
            @RequestParam(required = false) Integer durationSec) {
        return CommonResult.success(dramaStoryboardService.startDraft(id, durationSec));
    }

    @Operation(summary = "重新编译本集全部分镜（预览，不调视频模型）")
    @PostMapping("/episodes/{id}/storyboard/compile")
    public CommonResult<DramaStoryboardResp> compileStoryboard(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.compileEpisode(id));
    }

    @Operation(summary = "新增一镜；可插到指定镜之后，缺省挂到最后一场，人物/道具/地点沿用前一镜")
    @PostMapping("/episodes/{id}/shots")
    public CommonResult<DramaShotResp> createShot(
            @PathVariable Long id,
            @RequestBody(required = false) DramaShotCreateReq req) {
        return CommonResult.success(dramaStoryboardService.createShot(id, req));
    }

    @Operation(summary = "删除分镜并重排序号；生成中 / 已确认 / 最后一镜拒绝；已出片可删，本集成片作废")
    @DeleteMapping("/shots/{id}")
    public CommonResult<DramaStoryboardResp> deleteShot(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.deleteShot(id));
    }

    @Operation(summary = "更新分镜台词/时长/衔接/参考资产；视频 prompt 由编译器重算")
    @PutMapping("/shots/{id}")
    public CommonResult<DramaShotResp> updateShot(
            @PathVariable Long id,
            @Valid @RequestBody DramaShotSaveReq req) {
        return CommonResult.success(dramaStoryboardService.updateShot(id, req));
    }

    @Operation(summary = "编译单镜（预览 compiled_prompt，不调 MiniMax）")
    @PostMapping("/shots/{id}/compile")
    public CommonResult<DramaShotResp> compileShot(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.compileShot(id));
    }

    @Operation(summary = "确认分镜；还没有参考图的资产不能进分镜参考")
    @PostMapping("/shots/{id}/confirm")
    public CommonResult<DramaShotResp> confirmShot(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.confirmShot(id));
    }

    @Operation(summary = "单镜出片；按镜头 videoGenerateMode 走参考图或首尾帧")
    @PostMapping("/shots/{id}/generate")
    public CommonResult<DramaShotGenerateResp> generateShot(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.generateShot(id));
    }

    @Operation(summary = "批量出片：同场选中镜，服务端编排并发或串行")
    @PostMapping("/episodes/{id}/shots/batch-generate")
    public CommonResult<DramaShotBatchGenerateResp> batchGenerateShots(
            @PathVariable Long id, @Valid @RequestBody DramaShotBatchGenerateReq req) {
        return CommonResult.success(dramaShotBatchGenerateService.create(id, req));
    }

    @Operation(summary = "本集当前 RUNNING 的批量出片任务（供刷新恢复）")
    @GetMapping("/episodes/{id}/shots/batch-generate/active")
    public CommonResult<DramaShotBatchGenerateResp> getActiveBatchGenerate(@PathVariable Long id) {
        return CommonResult.success(dramaShotBatchGenerateService.findActive(id));
    }

    @Operation(summary = "查询批量出片任务进度（RUNNING 时顺带推进）")
    @GetMapping("/episodes/{id}/shots/batch-generate/{jobId}")
    public CommonResult<DramaShotBatchGenerateResp> getBatchGenerateJob(
            @PathVariable Long id, @PathVariable Long jobId) {
        return CommonResult.success(dramaShotBatchGenerateService.getJob(id, jobId));
    }

    @Operation(summary = "自动 i2i 重画本镜开场静帧（不开始出视频）")
    @PostMapping("/shots/{id}/first-frame/generate")
    public CommonResult<DramaShotGenerateResp> generateShotOpeningStill(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.generateOpeningStill(id));
    }

    @Operation(summary = "上传本镜开场首帧（覆盖自动静帧或链式落幅）")
    @PostMapping("/shots/{id}/first-frame")
    public CommonResult<DramaShotResp> uploadShotFirstFrame(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return CommonResult.success(dramaStoryboardService.uploadFirstFrame(id, file));
    }

    @Operation(summary = "把账号素材库图片设为本镜首帧")
    @PostMapping("/shots/{id}/first-frame/from-asset")
    public CommonResult<DramaShotResp> useAssetShotFirstFrame(
            @PathVariable Long id,
            @Valid @RequestBody DramaShotFrameAssetReq req) {
        return CommonResult.success(dramaStoryboardService.useAssetFirstFrame(id, req.getAssetId()));
    }

    @Operation(summary = "清除本镜开场静帧，改回同场上一镜落幅或下次自动重画")
    @DeleteMapping("/shots/{id}/first-frame")
    public CommonResult<DramaShotResp> clearShotFirstFrame(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.clearFirstFrame(id));
    }

    @Operation(summary = "把上一镜尾帧复制到本镜首帧；没有用户尾帧时用成片落幅")
    @PostMapping("/shots/{id}/first-frame/from-prev-end")
    public CommonResult<DramaShotResp> usePrevEndFirstFrame(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.usePrevEndFrame(id));
    }

    @Operation(summary = "上传本镜尾帧")
    @PostMapping("/shots/{id}/end-frame")
    public CommonResult<DramaShotResp> uploadShotEndFrame(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return CommonResult.success(dramaStoryboardService.uploadEndFrame(id, file));
    }

    @Operation(summary = "把账号素材库图片设为本镜尾帧")
    @PostMapping("/shots/{id}/end-frame/from-asset")
    public CommonResult<DramaShotResp> useAssetShotEndFrame(
            @PathVariable Long id,
            @Valid @RequestBody DramaShotFrameAssetReq req) {
        return CommonResult.success(dramaStoryboardService.useAssetEndFrame(id, req.getAssetId()));
    }

    @Operation(summary = "清除本镜用户尾帧")
    @DeleteMapping("/shots/{id}/end-frame")
    public CommonResult<DramaShotResp> clearShotEndFrame(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.clearEndFrame(id));
    }

    @Operation(summary = "改用同场上一镜落幅作本镜首帧（换场不可用）")
    @PostMapping("/shots/{id}/first-frame/from-prev")
    public CommonResult<DramaShotResp> usePrevTailFirstFrame(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.usePrevTail(id));
    }

    @Operation(summary = "查询分镜出片任务；成功后写入 video_key")
    @GetMapping("/shots/{id}/generate/tasks/{taskId}")
    public CommonResult<DramaShotGenerateResp> getShotGenerateTask(
            @PathVariable Long id,
            @PathVariable Long taskId) {
        return CommonResult.success(dramaStoryboardService.getGenerateTask(id, taskId));
    }

    @Operation(summary = "分镜生成记录列表（新→旧）")
    @GetMapping("/shots/{id}/generations")
    public CommonResult<List<DramaShotGenerationResp>> listShotGenerations(@PathVariable Long id) {
        return CommonResult.success(dramaStoryboardService.listGenerations(id));
    }

    @Operation(summary = "选用一条分镜生成记录为最终；已锁定拒绝")
    @PostMapping("/shots/{id}/generations/{gid}/select")
    public CommonResult<DramaShotResp> selectShotGeneration(
            @PathVariable Long id,
            @PathVariable Long gid) {
        return CommonResult.success(dramaStoryboardService.selectGeneration(id, gid));
    }

    @Operation(summary = "删除分镜生成记录；锁定时仅可删未选用")
    @DeleteMapping("/shots/{id}/generations/{gid}")
    public CommonResult<Void> deleteShotGeneration(
            @PathVariable Long id,
            @PathVariable Long gid) {
        dramaStoryboardService.deleteGeneration(id, gid);
        return CommonResult.success();
    }

    // 暂关分镜预览字幕，保证出片视频画面/播放器均无字幕；恢复时取消注释。
    // @Operation(summary = "当前分镜中文字幕（按实际视频时长生成 WebVTT）")
    // @GetMapping(value = "/shots/{id}/captions.vtt", produces = "text/vtt;charset=UTF-8")
    // public ResponseEntity<String> getShotCaptions(@PathVariable Long id) {
    //     return vtt(dramaCaptionService.shotVtt(id));
    // }

    @Operation(summary = "本集成片状态（指纹过期、缺镜清单）")
    @GetMapping("/episodes/{id}/film")
    public CommonResult<DramaFilmResp> getFilm(@PathVariable Long id) {
        return CommonResult.success(dramaFilmService.get(id));
    }

    @Operation(summary = "按镜序拼接本集已出片镜头；可传 shotIds 选择性入片，缺镜仍可预览")
    @PostMapping("/episodes/{id}/film/assemble")
    public CommonResult<DramaFilmResp> assembleFilm(
            @PathVariable Long id,
            @RequestBody(required = false) DramaFilmAssembleReq req) {
        return CommonResult.success(dramaFilmService.assemble(id, req == null ? null : req.getShotIds()));
    }

    // 成片字幕暂保留接口；若也要关掉，一并取消注释块即可。
    @Operation(summary = "本集成片中文字幕（按镜头实际时长累计 WebVTT）")
    @GetMapping(value = "/episodes/{id}/film/captions.vtt", produces = "text/vtt;charset=UTF-8")
    public ResponseEntity<String> getFilmCaptions(@PathVariable Long id) {
        return vtt(dramaCaptionService.episodeVtt(id));
    }

    private static ResponseEntity<String> vtt(String body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8"))
                .body(body);
    }
}
