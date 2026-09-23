package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.alignment.AlignmentResult;
import cn.aigc.dit.generate.alignment.AlignmentToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignmentConfidenceEvaluatorTest {

    @Test
    void rejectsLowCoverageAndAcceptsMonotonicResult() {
        GenerateProperties properties = new GenerateProperties();
        properties.getForcedAlignment().setMinCoverage(0.9D);
        properties.getForcedAlignment().setMinConfidence(0.7D);
        AlignmentConfidenceEvaluator evaluator = new AlignmentConfidenceEvaluator(properties);

        AlignmentResult lowCoverage = new AlignmentResult(true, 0.95D, 0.5D,
                List.of(new AlignmentToken("你", 10, 50, 0.95D, 0)), null, "test");
        assertFalse(evaluator.evaluate(lowCoverage, 2, 1000).accepted());

        AlignmentResult accepted = new AlignmentResult(true, 0.95D, 1D,
                List.of(new AlignmentToken("你", 10, 50, 0.95D, 0),
                        new AlignmentToken("好", 55, 90, 0.95D, 1)), null, "test");
        assertTrue(evaluator.evaluate(accepted, 2, 1000).accepted());
    }
}
