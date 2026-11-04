package cn.aigc.dit.server.drama.entity;

import cn.aigc.dit.server.drama.DramaShotBeats;
import cn.aigc.dit.server.mybatis.DramaShotBeatsTypeHandler;
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

    /** 分镜内部 1–3 个镜头。空则编译和页面仍走单句 narrative_goal。 */
    @TableField(typeHandler = DramaShotBeatsTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private DramaShotBeats shotBeats;

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

    /** 用户可见、可编辑的核心正文。叙述里是 @名字，台词是普通名字。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String visiblePrompt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String promptOverride;
    private String status;

    /** 允许清空任务号，否则删光生成记录后刷新会把成功任务再写回，预览视频跟着回来。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long genTaskId;

    /** 允许清空成片，否则删光生成记录后预览仍读到旧视频，对象存储也无法释放该文件。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
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

    /** 用户设置的尾帧，不是出片落幅 videoTailKey。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String endFrameKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String endFrameOrigin;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String videoGenerateMode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean videoChainPrevTail;

    /** 未出片应为空；默认 NOT_NULL 会跳过 null，删光记录后仍像出过片。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String videoGeneratedMode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long chainedFromShotId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String chainedFromTailKey;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
