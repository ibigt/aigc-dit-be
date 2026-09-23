package cn.aigc.dit.generate.alignment;

import java.nio.file.Path;

/** In-process forced alignment; it does not perform speech recognition. */
public interface ForcedAlignmentService {

    AlignmentResult align(Path audioFile, String transcript);
}
