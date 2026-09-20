package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "拼接成片请求；shotIds 为空或未传时拼接本集全部已出片镜头")
public class DramaFilmAssembleReq {

    @Schema(description = "入片分镜 id（须属本集且已出片）；按镜序拼接，忽略请求顺序")
    private List<Long> shotIds;
}
