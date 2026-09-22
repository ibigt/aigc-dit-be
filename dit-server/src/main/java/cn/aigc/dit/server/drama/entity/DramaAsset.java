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
    private String imageStorageKey;
    private Long imageAssetId;
    private Long genTaskId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
