package cn.aigc.dit.server.script;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.script.model.req.ScriptChatReq;
import cn.aigc.dit.server.script.model.req.ScriptDocumentSaveReq;
import cn.aigc.dit.server.script.model.req.ScriptModelUpdateReq;
import cn.aigc.dit.server.script.model.req.ScriptTaskCreateReq;
import cn.aigc.dit.server.script.model.req.ScriptTitleUpdateReq;
import cn.aigc.dit.server.script.model.resp.ScriptTaskResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "剧本创作")
@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptTaskService service;

    @Operation(summary = "剧本任务列表")
    @GetMapping("/tasks")
    public CommonResult<List<ScriptTaskResp>> list(@RequestParam(required = false) String q) {
        return CommonResult.success(service.list(q));
    }

    @Operation(summary = "剧本任务详情")
    @GetMapping("/tasks/{id}")
    public CommonResult<ScriptTaskResp> get(@PathVariable Long id) {
        return CommonResult.success(service.get(id));
    }

    @Operation(summary = "新建剧本任务")
    @PostMapping("/tasks")
    public CommonResult<ScriptTaskResp> create(@Valid @RequestBody ScriptTaskCreateReq req) {
        return CommonResult.success(service.create(req));
    }

    @Operation(summary = "保存剧本文档版本")
    @PutMapping("/tasks/{id}/document")
    public CommonResult<ScriptTaskResp> saveDocument(
            @PathVariable Long id, @Valid @RequestBody ScriptDocumentSaveReq req) {
        return CommonResult.success(service.saveDocument(id, req));
    }

    @Operation(summary = "切换剧本任务文本模型")
    @PutMapping("/tasks/{id}/model")
    public CommonResult<ScriptTaskResp> updateModel(
            @PathVariable Long id, @Valid @RequestBody ScriptModelUpdateReq req) {
        return CommonResult.success(service.updateModel(id, req));
    }

    @Operation(summary = "修改剧本名称")
    @PutMapping("/tasks/{id}/title")
    public CommonResult<ScriptTaskResp> updateTitle(
            @PathVariable Long id, @Valid @RequestBody ScriptTitleUpdateReq req) {
        return CommonResult.success(service.updateTitle(id, req));
    }

    @Operation(summary = "运行灵感/改编/评估工作流")
    @PostMapping("/tasks/{id}/generate")
    public CommonResult<ScriptTaskResp> generate(@PathVariable Long id) {
        return CommonResult.success(service.generate(id));
    }

    @Operation(summary = "完成剧本任务")
    @PostMapping("/tasks/{id}/complete")
    public CommonResult<ScriptTaskResp> complete(@PathVariable Long id) {
        return CommonResult.success(service.complete(id));
    }

    @Operation(summary = "删除剧本任务")
    @DeleteMapping("/tasks/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return CommonResult.success();
    }

    @Operation(summary = "补全人物设定与分集大纲")
    @PostMapping("/tasks/{id}/structure")
    public CommonResult<ScriptTaskResp> repairStructure(@PathVariable Long id) {
        return CommonResult.success(service.repairStructure(id));
    }

    @Operation(summary = "续写下一集并同步剧本结构")
    @PostMapping("/tasks/{id}/continue")
    public CommonResult<ScriptTaskResp> continueEpisode(@PathVariable Long id) {
        return CommonResult.success(service.continueEpisode(id));
    }

    @Operation(summary = "非流式剧本助手对话")
    @PostMapping("/tasks/{id}/chat")
    public CommonResult<String> chat(@PathVariable Long id, @Valid @RequestBody ScriptChatReq req) {
        return CommonResult.success(service.chat(id, req));
    }

    @Operation(summary = "流式剧本助手对话")
    @PostMapping(value = "/tasks/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable Long id, @Valid @RequestBody ScriptChatReq req) {
        return service.streamChat(id, req);
    }

    @Operation(summary = "将剧本任务创建为关联短剧项目")
    @PostMapping("/tasks/{id}/create-drama")
    public CommonResult<DramaSeriesResp> createDrama(@PathVariable Long id) {
        return CommonResult.success(service.createDrama(id));
    }
}
