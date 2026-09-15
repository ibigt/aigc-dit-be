package cn.aigc.dit.generate.audio;

/**
 * Prepares an audio reference before it is embedded in a generation request.
 */
public interface AudioReferenceProcessor {

    ProcessedAudio process(byte[] source, String sourceName, long maxDurationMillis);

    record ProcessedAudio(byte[] bytes, String contentType) {
    }
}
