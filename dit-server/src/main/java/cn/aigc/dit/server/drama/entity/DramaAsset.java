package cn.aigc.dit.server.drama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("drama_asset")
public class DramaAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seriesId;
    private Long episodeId;
    private Long userId;
    private String name;
    private String type;
    private String stateTag;

    /** 版本展示名（湿身/校服）；基础版可空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String versionLabel;

    /** 延伸父版本；基础版为空。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long parentAssetId;

    /** 允许保存空值，否则清空提示词无法落库，列表会一直读到旧长文案。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    /** 允许清空设定摘要，否则删掉后再打开仍是旧摘要。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String identityInvariants;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String imagePrompt;
    private String voiceLock;
    private String voiceReferenceKey;
    private String voiceReferenceMime;
    private Boolean mustSplit;
    private Boolean needsHuman;
    private String status;
    private Integer identityVersion;

    /** 允许清空选用图，否则删光生成记录后列表仍读到旧预览。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String imageStorageKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long imageAssetId;

    /** 允许清空任务号，否则锁定资产上的陈旧任务号清不掉，迟到的回调仍会重开资产。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long genTaskId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long selectedGenerationId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
