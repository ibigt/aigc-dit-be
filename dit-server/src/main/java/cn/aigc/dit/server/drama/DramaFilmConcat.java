package cn.aigc.dit.server.drama;

import java.nio.file.Path;
import java.util.List;

/**
 * 按传入顺序硬切拼接镜头。实现可 mock，便于断言镜序。
 */
public interface DramaFilmConcat {

    void concat(List<Path> inputs, Path output);
}
