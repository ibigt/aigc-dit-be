package cn.aigc.dit.generate.vad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import cn.aigc.dit.generate.GenerateProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.lang.reflect.Array;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-process Silero VAD runner. The deployment supplies the ONNX model path;
 * no Python runtime or sidecar is required.
 */
@Slf4j
@Component
public class SileroVadDetector implements VoiceActivityDetector {

    private final GenerateProperties.Vad properties;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private volatile OrtSession session;
    private volatile String loadedModelPath;

    public SileroVadDetector(GenerateProperties properties) {
        this.properties = properties.getVad() == null ? new GenerateProperties.Vad() : properties.getVad();
    }

    @Override
    public List<SpeechSegment> detect(Path audioFile) {
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            throw new IllegalArgumentException("audio file does not exist");
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Silero VAD is disabled");
        }
        int sampleRate = properties.getSampleRate();
        if (sampleRate != 16000) {
            throw new IllegalStateException("Silero VAD currently requires 16000 Hz audio");
        }
        float[] samples = readPcm16Mono(audioFile, sampleRate);
        if (samples.length == 0) {
            return List.of();
        }
        try {
            return normalize(runModel(samples, sampleRate), samples.length, sampleRate);
        } catch (OrtException e) {
            throw new IllegalStateException("Silero VAD inference failed", e);
        }
    }

    private List<SpeechSegment> runModel(float[] samples, int sampleRate) throws OrtException {
        OrtSession current = session();
        SetNames names = SetNames.resolve(current);
        int frameSamples = Math.max(128, properties.getFrameSamples());
        float[] state = new float[2 * 1 * 128];
        List<SpeechSegment> raw = new ArrayList<>();
        long openStart = -1L;
        for (int offset = 0; offset < samples.length; offset += frameSamples) {
            float[] frame = new float[frameSamples];
            int copy = Math.min(frameSamples, samples.length - offset);
            System.arraycopy(samples, offset, frame, 0, copy);
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            try (OnnxTensor input = OnnxTensor.createTensor(environment,
                    FloatBuffer.wrap(frame), new long[]{1, frameSamples});
                 OnnxTensor stateTensor = OnnxTensor.createTensor(environment,
                         FloatBuffer.wrap(state), new long[]{2, 1, 128});
                 OnnxTensor sampleRateTensor = OnnxTensor.createTensor(environment,
                         LongBuffer.wrap(new long[]{sampleRate}), new long[]{1})) {
                inputs.put(names.input(), input);
                inputs.put(names.state(), stateTensor);
                inputs.put(names.sampleRate(), sampleRateTensor);
                try (OrtSession.Result result = current.run(inputs)) {
                    float probability = scalar(result.get(0).getValue());
                    if (result.size() > 1) {
                        float[] nextState = flatten(result.get(1).getValue());
                        if (nextState.length == state.length) {
                            state = nextState;
                        }
                    }
                    long frameStart = Math.round(offset * 1000D / sampleRate);
                    if (probability >= properties.getThreshold()) {
                        if (openStart < 0) {
                            openStart = frameStart;
                        }
                    } else if (openStart >= 0) {
                        raw.add(new SpeechSegment(openStart, Math.max(openStart + 1, frameStart)));
                        openStart = -1L;
                    }
                }
            }
        }
        if (openStart >= 0) {
            long end = Math.round(samples.length * 1000D / sampleRate);
            raw.add(new SpeechSegment(openStart, Math.max(openStart + 1, end)));
        }
        return raw;
    }

    private OrtSession session() throws OrtException {
        String configured = properties.getModelPath() == null ? "" : properties.getModelPath().trim();
        if (configured.isBlank()) {
            throw new IllegalStateException("SILERO_VAD_MODEL_PATH is not configured");
        }
        Path model = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(model)) {
            throw new IllegalStateException("Silero VAD model not found: " + model);
        }
        OrtSession current = session;
        if (current != null && model.toString().equals(loadedModelPath)) {
            return current;
        }
        synchronized (this) {
            current = session;
            if (current == null || !model.toString().equals(loadedModelPath)) {
                if (current != null) {
                    current.close();
                }
                session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
                loadedModelPath = model.toString();
                log.info("loaded Silero VAD ONNX model {}", model);
            }
            return session;
        }
    }

    private List<SpeechSegment> normalize(List<SpeechSegment> raw, int sampleCount, int sampleRate) {
        if (raw.isEmpty()) {
            return List.of();
        }
        long durationMs = Math.max(1L, Math.round(sampleCount * 1000D / sampleRate));
        long padding = Math.max(0, properties.getPaddingMs());
        long mergeGap = Math.max(0, properties.getMergeGapMs());
        long minSpeechMs = Math.max(1L, properties.getMinSpeechMs());
        List<SpeechSegment> sorted = raw.stream()
                .sorted(Comparator.comparingLong(SpeechSegment::startMs))
                // Drop short raw islands before padding; padding must not turn
                // a noise blip into a valid speech segment.
                .filter(item -> item.durationMs() >= minSpeechMs)
                .map(item -> new SpeechSegment(
                        Math.max(0, item.startMs() - padding),
                        Math.min(durationMs, item.endMs() + padding)))
                .toList();
        List<SpeechSegment> merged = new ArrayList<>();
        for (SpeechSegment item : sorted) {
            if (merged.isEmpty()) {
                merged.add(item);
                continue;
            }
            SpeechSegment previous = merged.get(merged.size() - 1);
            if (item.startMs() <= previous.endMs() + mergeGap) {
                merged.set(merged.size() - 1,
                        new SpeechSegment(previous.startMs(), Math.max(previous.endMs(), item.endMs())));
            } else {
                merged.add(item);
            }
        }
        return List.copyOf(merged);
    }

    private static float[] readPcm16Mono(Path audioFile, int sampleRate) {
        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                1,
                2,
                sampleRate,
                false);
        try (AudioInputStream source = AudioSystem.getAudioInputStream(audioFile.toFile());
             AudioInputStream pcm = AudioSystem.getAudioInputStream(target, source)) {
            byte[] bytes = pcm.readAllBytes();
            float[] samples = new float[bytes.length / 2];
            for (int i = 0; i < samples.length; i++) {
                int low = bytes[i * 2] & 0xff;
                int high = bytes[i * 2 + 1];
                short value = (short) ((high << 8) | low);
                samples[i] = value / 32768F;
            }
            return samples;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read PCM WAV audio: " + audioFile, e);
        }
    }

    private static float scalar(Object value) {
        float[] values = flatten(value);
        return values.length == 0 ? 0F : values[0];
    }

    private static float[] flatten(Object value) {
        if (value == null) {
            return new float[0];
        }
        if (value instanceof float[] values) {
            return values;
        }
        if (value instanceof Number number) {
            return new float[]{number.floatValue()};
        }
        if (!value.getClass().isArray()) {
            return new float[0];
        }
        List<Float> result = new ArrayList<>();
        flattenInto(value, result);
        float[] values = new float[result.size()];
        for (int i = 0; i < result.size(); i++) {
            values[i] = result.get(i);
        }
        return values;
    }

    private static void flattenInto(Object value, List<Float> output) {
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
            Object item = Array.get(value, i);
            if (item instanceof Number number) {
                output.add(number.floatValue());
            } else if (item != null && item.getClass().isArray()) {
                flattenInto(item, output);
            }
        }
    }

    private record SetNames(String input, String state, String sampleRate) {

        static SetNames resolve(OrtSession session) throws OrtException {
            List<String> names = new ArrayList<>(session.getInputInfo().keySet());
            String input = find(names, "input");
            String state = find(names, "state");
            String sampleRate = find(names, "sr");
            if (input == null || state == null || sampleRate == null) {
                throw new IllegalStateException("unsupported Silero VAD model inputs: " + names);
            }
            return new SetNames(input, state, sampleRate);
        }

        private static String find(List<String> names, String wanted) {
            return names.stream()
                    .filter(name -> name.equalsIgnoreCase(wanted)
                            || name.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        }
    }
}
