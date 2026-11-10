package cn.aigc.dit.server.drama.entity;

import cn.aigc.dit.server.mybatis.JsonLongListTypeHandler;
import cn.aigc.dit.server.mybatis.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "drama_episode", autoResultMap = true)
public class DramaEpisode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seriesId;
    private Long userId;
    private Integer episodeNumber;
    private String name;
    private String sourceText;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> script;

    private String engine;
    private String status;
    private Long inheritedFromEpisodeId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String filmKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String filmPosterKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String filmFingerprint;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime filmAssembledAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS, typeHandler = JsonLongListTypeHandler.class)
    private List<Long> filmShotIds;

    /** 分镜草稿锁：只能由 claim/release/fail 三条专用 SQL 写，稀疏更新不许碰它。 */
    private OffsetDateTime storyboardDraftingAt;

    /**
     * 分析锁的占用时间：前端用它算「已等待多久」，分析跑多久都不改。
     * 只能由 claim/release/fail 三条专用 SQL 写，稀疏更新不许碰它。
     */
    private OffsetDateTime scriptAnalyzingAt;

    /**
     * 分析心跳：分析期间每分钟续期一次。
     *
     * <p>{@code script_analyzing_at} 记的是开始时间（前端用它算已等待时长），
     * 分析跑久了不能改它；是否「还在分析」改看这个心跳列，超过 15 分钟没续期才判过期。
     */
    private OffsetDateTime scriptAnalyzingHeartbeatAt;

    /** 上一次分析失败的原因。只能由 claim/release/fail 三条专用 SQL 写。 */
    private String scriptAnalyzeError;

    /** 分镜草稿失败的原因。只能由 claim/release/fail 三条专用 SQL 写。 */
    private String storyboardDraftError;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
