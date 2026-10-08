package cn.aigc.dit.server.drama.entity;

import cn.aigc.dit.server.mybatis.JsonLongListTypeHandler;
import cn.aigc.dit.server.mybatis.JsonStringListTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@TableName(value = "drama_shot", autoResultMap = true)
public class DramaShot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seriesId;
    private Long episodeId;
    private Long chapterId;
    private Long userId;
    private Integer shotIndex;
    private String title;
    private Integer durationSec;
    private String narrativeGoal;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cameraMotion;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String ambianceAudio;

    private String scene;
    private String dialogue;
    private String continuityIn;
    private String continuityOut;

    @TableField(typeHandler = JsonLongListTypeHandler.class)
    private List<Long> referenceAssetIds = new ArrayList<>();

    @TableField(typeHandler = JsonStringListTypeHandler.class)
    private List<String> characterNames = new ArrayList<>();

    @TableField(typeHandler = JsonStringListTypeHandler.class)
    private List<String> propNames = new ArrayList<>();

    /** 允许清空场景名；默认 NOT_NULL 会跳过 null，删场景后仍会从库里读回旧值。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String locationName;
    private Integer castCount;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String compiledPrompt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String compiledFingerprint;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String promptOverride;
    private String status;
    private Long genTaskId;
    private String videoKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long selectedGenerationId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String videoPosterKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String videoTailKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String firstFrameKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String firstFrameOrigin;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String videoGenerateMode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean videoChainPrevTail;

    private String videoGeneratedMode;
    private Long chainedFromShotId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String chainedFromTailKey;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
