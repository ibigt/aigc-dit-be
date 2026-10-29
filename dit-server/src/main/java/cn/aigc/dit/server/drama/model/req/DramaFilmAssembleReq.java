package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "拼接成片请求；shotIds 省略（null）时拼本集全部已出片；传空数组会报错")
public class DramaFilmAssembleReq {

    @Schema(description = "入片分镜 id（须属本集且已出片）；按镜序拼接，忽略请求顺序；不可传空数组")
    private List<Long> shotIds;

    @Schema(description = "衔接方式：顺接（默认，裁气口并淡化接点声音）或直拼（原片硬切）", example = "顺接")
    private String mode;
}
