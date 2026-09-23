package cn.aigc.dit.generate.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a mono 16-bit PCM WAV without invoking another process. */
public final class Pcm16WavWriter {

    private Pcm16WavWriter() {
    }

    public static void write(Path output, float[] samples, int sampleRate) throws IOException {
        int dataSize = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataSize);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(dataSize);
        for (float sample : samples) {
            float clipped = Math.max(-1F, Math.min(1F, sample));
            buffer.putShort((short) Math.round(clipped * 32767F));
        }
        Files.write(output, buffer.array());
    }
}
