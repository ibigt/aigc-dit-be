package cn.aigc.dit.server.drama;

import java.nio.file.Path;
import java.util.List;

/**
 * 按传入顺序拼接镜头。直拼优先流复制；顺接按裁切窗重编码。实现可 mock，便于断言镜序。
 */
public interface DramaFilmConcat {

    void concat(List<Path> inputs, Path output);

    /**
     * 顺接重编码：按 span 裁头尾，统一画幅与帧率，接点做短音频淡化。
     */
    void concatSmooth(List<Path> inputs, List<DramaFilmModes.ClipSpan> spans, String aspectRatio, Path output);
}
