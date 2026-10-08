package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "更新分镜（台词 / 本镜动作 / 场景 / 时长 / 衔接 / 参考资产）；视频 prompt 由编译器重算")
public class DramaShotSaveReq {

    @Size(max = 128)
    private String title;

    @Schema(description = "5–15 秒，逐秒可选")
    private Integer durationSec;

    private String narrativeGoal;

    @Schema(description = "景别与运镜，如「特写，静止」")
    private String cameraMotion;

    @Schema(description = "画内环境声，不要 BGM 和台词")
    private String ambianceAudio;

    private String scene;
    private String dialogue;

    @Size(max = 256)
    private String continuityIn;

    @Size(max = 256)
    private String continuityOut;

    @Schema(description = "已锁定资产 id，顺序 = Picture 序号")
    private List<Long> referenceAssetIds;

    private List<String> characterNames;
    private List<String> propNames;

    @Size(max = 128)
    private String locationName;

    @Schema(description = "手动覆盖视频提示词；传非空=启用覆盖并跳过自动编译，传空串=清除覆盖回到自动编译，不传=不改动")
    @Size(max = 4000)
    private String promptOverride;

    @Schema(description = "本镜出片方式：r2v / i2v / inherit（跟随系列默认）；不传=不改动")
    @Size(max = 16)
    private String videoGenerateMode;

    @Schema(description = "本镜参考图是否衔接上一镜落幅；仅 r2v。不传=不改动；跟随默认时随 inherit 清空")
    private Boolean videoChainPrevTail;
}
