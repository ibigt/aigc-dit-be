package cn.aigc.dit.generate.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;

/** Small in-process reader for the 16 kHz mono PCM files produced by FFmpeg. */
public record Pcm16Audio(float[] samples, int sampleRate) {

    public static Pcm16Audio read(Path audioFile, int expectedSampleRate) {
        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                expectedSampleRate,
                16,
                1,
                2,
                expectedSampleRate,
                false);
        try (AudioInputStream source = AudioSystem.getAudioInputStream(audioFile.toFile());
             AudioInputStream pcm = AudioSystem.getAudioInputStream(target, source)) {
            byte[] bytes = pcm.readAllBytes();
            float[] samples = new float[bytes.length / 2];
            for (int i = 0; i < samples.length; i++) {
                int low = bytes[i * 2] & 0xff;
                int high = bytes[i * 2 + 1];
                samples[i] = (short) ((high << 8) | low) / 32768F;
            }
            return new Pcm16Audio(samples, expectedSampleRate);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read PCM WAV audio: " + audioFile, e);
        }
    }

    public long durationMs() {
        return Math.round(samples.length * 1000D / sampleRate);
    }
}
