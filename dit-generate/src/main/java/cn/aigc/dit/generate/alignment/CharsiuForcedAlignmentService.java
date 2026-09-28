package cn.aigc.dit.generate.alignment;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.audio.Pcm16Audio;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of the Mandarin Charsiu frame-classification aligner.
 *
 * The model is not an ASR model: it predicts phone probabilities per frame.
 * G2P turns the known transcript into Mandarin phone ids and DTW maps those
 * phones to the audio frames.
 */
@Slf4j
@Component
public class CharsiuForcedAlignmentService implements ForcedAlignmentService {

    private static final String MODEL_VERSION = "zh_w2v2_tiny_fc_10ms";
    private static final String SIL = "[SIL]";
    private static final String UNK = "<UNK>";
    private static final String BOS = "시";
    private static final String EOS = "끝";
    private static final String SPLIT = "▁";

    private final GenerateProperties.ForcedAlignment properties;
    private final ObjectMapper objectMapper;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private volatile OrtSession session;
    private volatile String loadedModelPath;
    private volatile G2pm g2pm;
    private volatile Map<String, Integer> phoneVocab;
    private volatile Map<Integer, String> idToPhone;

    public CharsiuForcedAlignmentService(GenerateProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.getForcedAlignment() == null
                ? new GenerateProperties.ForcedAlignment() : properties.getForcedAlignment();
        this.objectMapper = objectMapper;
    }

    @Override
    public AlignmentResult align(Path audioFile, String transcript) {
        String version = MODEL_VERSION;
        if (!properties.isEnabled()) {
            return AlignmentResult.failure("FORCED_ALIGNMENT_DISABLED", version);
        }
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            return AlignmentResult.failure("AUDIO_NOT_FOUND", version);
        }
        if (transcript == null || transcript.isBlank()) {
            return AlignmentResult.failure("TRANSCRIPT_EMPTY", version);
        }
        try {
            Pcm16Audio audio = Pcm16Audio.read(audioFile, properties.getSampleRate());
            Target target = target(transcript);
            if (target.characters().isEmpty() || target.phoneIds().isEmpty()) {
                return AlignmentResult.failure("TRANSCRIPT_NOT_SUPPORTED", version);
            }
            ModelOutput output = infer(audio.samples());
            if (output.frames() < target.phoneIds().size()) {
                return AlignmentResult.failure("AUDIO_TOO_SHORT_FOR_TRANSCRIPT", version);
            }
            return alignFrames(output.probabilities(), output.frames(), output.vocabSize(), target,
                    properties.getFrameMs(), version);
        } catch (Exception e) {
            log.warn("forced alignment failed for {}", audioFile, e);
            return AlignmentResult.failure(reason(e), version);
        }
    }

    private AlignmentResult alignFrames(float[] probabilities, int frames, int vocabSize, Target target,
                                         int frameMs, String version) {
        int[] predicted = new int[frames];
        for (int frame = 0; frame < frames; frame++) {
            int best = 0;
            for (int id = 1; id < vocabSize; id++) {
                if (probabilities[frame * vocabSize + id] > probabilities[frame * vocabSize + best]) {
                    best = id;
                }
            }
            predicted[frame] = best;
        }

        int silenceId = phoneVocab.getOrDefault(SIL, 1);
        boolean[] silence = new boolean[frames];
        for (int start = 0; start < frames;) {
            int end = start + 1;
            while (end < frames && predicted[end] == predicted[start]) end++;
            if (predicted[start] == silenceId && end - start >= Math.max(1, properties.getSilenceThresholdFrames())) {
                Arrays.fill(silence, start, end, true);
            }
            start = end;
        }

        List<Integer> nonSilenceFrames = new ArrayList<>();
        for (int frame = 0; frame < frames; frame++) {
            if (!silence[frame]) nonSilenceFrames.add(frame);
        }
        if (nonSilenceFrames.size() < target.phoneIds().size()) {
            return AlignmentResult.failure("NOT_ENOUGH_SPEECH_FRAMES", version);
        }

        int[] path = forcedAlign(probabilities, vocabSize, nonSilenceFrames, target.phoneIds());
        int phoneCount = target.phoneIds().size();
        int[] startFrame = new int[phoneCount];
        int[] endFrame = new int[phoneCount];
        double[] confidenceSum = new double[phoneCount];
        int[] frameCount = new int[phoneCount];
        Arrays.fill(startFrame, Integer.MAX_VALUE);

        for (int compact = 0; compact < path.length; compact++) {
            int phone = path[compact];
            int frame = nonSilenceFrames.get(compact);
            startFrame[phone] = Math.min(startFrame[phone], frame);
            endFrame[phone] = Math.max(endFrame[phone], frame + 1);
            confidenceSum[phone] += probabilities[frame * vocabSize + target.phoneIds().get(phone)];
            frameCount[phone]++;
        }

        List<AlignmentToken> tokens = new ArrayList<>();
        int covered = 0;
        double confidenceTotal = 0D;
        for (int charIndex = 0; charIndex < target.groups().size(); charIndex++) {
            List<Integer> phones = target.groups().get(charIndex);
            int first = Integer.MAX_VALUE;
            int last = -1;
            double sum = 0D;
            int count = 0;
            for (int phone : phones) {
                if (startFrame[phone] == Integer.MAX_VALUE) continue;
                first = Math.min(first, startFrame[phone]);
                last = Math.max(last, endFrame[phone]);
                sum += confidenceSum[phone];
                count += frameCount[phone];
            }
            if (first == Integer.MAX_VALUE || last <= first || count == 0) continue;
            long startMs = Math.max(0L, Math.round(first * (double) frameMs));
            long endMs = Math.max(startMs + 1L, Math.round(last * (double) frameMs));
            double tokenConfidence = sum / count;
            tokens.add(new AlignmentToken(target.characters().get(charIndex), startMs, endMs,
                    tokenConfidence, target.normalizedIndices().get(charIndex)));
            covered++;
            confidenceTotal += tokenConfidence;
        }
        double coverage = target.characters().isEmpty() ? 0D : covered / (double) target.characters().size();
        double confidence = tokens.isEmpty() ? 0D : confidenceTotal / tokens.size();
        if (tokens.isEmpty()) {
            return AlignmentResult.failure("NO_ALIGNED_TOKENS", version);
        }
        return new AlignmentResult(true, confidence, coverage, tokens, null, version);
    }

    /** DTW equivalent to charsiu-js forcedAlign: stay or advance one phone. */
    static int[] forcedAlign(float[] probabilities, int vocabSize, List<Integer> frameIndices, List<Integer> phoneIds) {
        int frames = frameIndices.size();
        int phones = phoneIds.size();
        double[] scores = new double[frames * phones];
        byte[] back = new byte[frames * phones];
        Arrays.fill(scores, Double.POSITIVE_INFINITY);
        scores[0] = -probabilities[frameIndices.get(0) * vocabSize + phoneIds.get(0)];
        for (int frame = 1; frame < frames; frame++) {
            int sourceFrame = frameIndices.get(frame);
            int maxPhone = Math.min(frame, phones - 1);
            for (int phone = 0; phone <= maxPhone; phone++) {
                double stay = scores[(frame - 1) * phones + phone];
                double advance = phone > 0
                        ? scores[(frame - 1) * phones + phone - 1] : Double.POSITIVE_INFINITY;
                if (advance <= stay) {
                    scores[frame * phones + phone] = advance
                            - probabilities[sourceFrame * vocabSize + phoneIds.get(phone)];
                    back[frame * phones + phone] = 1;
                } else if (stay < Double.POSITIVE_INFINITY) {
                    scores[frame * phones + phone] = stay
                            - probabilities[sourceFrame * vocabSize + phoneIds.get(phone)];
                }
            }
        }
        if (!Double.isFinite(scores[frames * phones - 1])) {
            throw new IllegalStateException("DTW path is not finite");
        }
        int[] path = new int[frames];
        int phone = phones - 1;
        for (int frame = frames - 1; frame >= 0; frame--) {
            path[frame] = phone;
            if (back[frame * phones + phone] == 1) phone--;
        }
        if (phone != 0) throw new IllegalStateException("DTW path did not reach first phone");
        return path;
    }

    private Target target(String transcript) {
        List<String> characters = transcript.codePoints().mapToObj(Character::toString).toList();
        List<String> pinyin = g2pm().convert(transcript);
        if (pinyin.size() != characters.size()) {
            throw new IllegalArgumentException("G2P character count mismatch");
        }
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> phoneIds = new ArrayList<>();
        List<String> supportedChars = new ArrayList<>();
        List<Integer> normalizedIndices = new ArrayList<>();
        int charIndex = 0;
        for (String character : characters) {
            String syllable = canonicalSyllable(pinyin.get(charIndex));
            if (!syllable.matches("[a-z:]+[1-5]")) {
                throw new IllegalArgumentException("unsupported transcript character: " + character);
            }
            List<String> phones = separateSyllable(syllable);
            List<Integer> group = new ArrayList<>();
            for (String phone : phones) {
                Integer id = phoneVocab.get(phone);
                if (id == null) throw new IllegalArgumentException("phone is not in vocab: " + phone);
                group.add(phoneIds.size());
                phoneIds.add(id);
            }
            groups.add(group);
            supportedChars.add(character);
            normalizedIndices.add(charIndex);
            charIndex++;
        }
        return new Target(supportedChars, groups, phoneIds, normalizedIndices);
    }

    private ModelOutput infer(float[] samples) throws OrtException {
        OrtSession current = session();
        String inputName = current.getInputNames().contains("input_values")
                ? "input_values" : current.getInputNames().iterator().next();
        // Charsiu expects a mono 16 kHz waveform whose samples are already in
        // [-1, 1]. Do not apply an extra z-score normalization here; the
        // wav2vec2 feature extractor in the ONNX model handles its own input
        // normalization and the JS reference passes the raw waveform.
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples),
                new long[]{1, samples.length});
             OrtSession.Result result = current.run(Map.of(inputName, input))) {
            Object value = result.get(0).getValue();
            float[] flattened = flatten(value);
            int vocabSize = inferVocabSize(current, flattened.length);
            if (vocabSize <= 0 || flattened.length % vocabSize != 0) {
                throw new IllegalStateException("unexpected forced-alignment logits shape");
            }
            int frames = flattened.length / vocabSize;
            return new ModelOutput(softmax(flattened, frames, vocabSize), frames, vocabSize);
        }
    }

    private static int inferVocabSize(OrtSession current, int flattened) {
        try {
            String outputName = current.getOutputNames().iterator().next();
            TensorInfo info = (TensorInfo) current.getOutputInfo().get(outputName).getInfo();
            long[] shape = info.getShape();
            if (shape.length > 0 && shape[shape.length - 1] > 0) return (int) shape[shape.length - 1];
        } catch (Exception ignored) {
            // The known Charsiu Mandarin model has 212 output phones.
        }
        return 212;
    }

    private static float[] softmax(float[] logits, int frames, int vocabSize) {
        float[] output = new float[logits.length];
        for (int frame = 0; frame < frames; frame++) {
            int base = frame * vocabSize;
            float max = -Float.MAX_VALUE;
            for (int id = 0; id < vocabSize; id++) max = Math.max(max, logits[base + id]);
            double sum = 0D;
            for (int id = 0; id < vocabSize; id++) {
                float value = (float) Math.exp(logits[base + id] - max);
                output[base + id] = value;
                sum += value;
            }
            for (int id = 0; id < vocabSize; id++) output[base + id] /= (float) sum;
        }
        return output;
    }

    private OrtSession session() throws OrtException {
        Path model = requiredPath(properties.getModelPath(), "FORCED_ALIGNMENT_MODEL_PATH");
        OrtSession current = session;
        if (current != null && model.toString().equals(loadedModelPath)) {
            return current;
        }
        synchronized (this) {
            current = session;
            if (current == null || !model.toString().equals(loadedModelPath)) {
                if (current != null) current.close();
                session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
                loadedModelPath = model.toString();
                log.info("loaded Chinese forced-alignment ONNX model {}", model);
            }
            return session;
        }
    }

    private G2pm g2pm() {
        G2pm current = g2pm;
        if (current != null && phoneVocab != null) return current;
        synchronized (this) {
            if (g2pm == null) {
                Path assets = requiredPath(properties.getAssetsPath(), "FORCED_ALIGNMENT_ASSETS_PATH");
                try {
                    phoneVocab = objectMapper.readValue(assets.resolve("vocab_zh.json").toFile(),
                            new TypeReference<Map<String, Integer>>() { });
                    Map<Integer, String> reverse = new HashMap<>();
                    phoneVocab.forEach((phone, id) -> reverse.put(id, phone));
                    idToPhone = reverse;
                    Map<String, List<String>> cedict = objectMapper.readValue(
                            assets.resolve("g2pm_cedict.json").toFile(), new TypeReference<>() { });
                    Map<String, Integer> char2idx = objectMapper.readValue(
                            assets.resolve("g2pm_char2idx.json").toFile(), new TypeReference<>() { });
                    List<String> idx2class = objectMapper.readValue(
                            assets.resolve("g2pm_idx2class.json").toFile(), new TypeReference<>() { });
                    Map<String, WeightSpec> manifest = objectMapper.readValue(
                            assets.resolve("g2pm_lstm.json").toFile(), new TypeReference<>() { });
                    byte[] bytes = Files.readAllBytes(assets.resolve("g2pm_lstm.bin"));
                    FloatBuffer floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    float[] all = new float[floatBuffer.remaining()];
                    floatBuffer.get(all);
                    Map<String, Weight> weights = new HashMap<>();
                    for (Map.Entry<String, WeightSpec> entry : manifest.entrySet()) {
                        WeightSpec spec = entry.getValue();
                        int length = Arrays.stream(spec.shape()).reduce(1, (a, b) -> a * b);
                        weights.put(entry.getKey(), new Weight(
                                Arrays.copyOfRange(all, spec.offset(), spec.offset() + length), spec.shape()));
                    }
                    g2pm = new G2pm(cedict, char2idx, idx2class, weights);
                } catch (Exception e) {
                    throw new IllegalStateException("cannot load Charsiu G2P assets from " + assets, e);
                }
            }
            return g2pm;
        }
    }

    private static Path requiredPath(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is not configured");
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.exists(path)) throw new IllegalStateException(name + " does not exist: " + path);
        return path;
    }

    private static float[] flatten(Object value) {
        if (value == null) return new float[0];
        if (value instanceof float[] values) return values;
        if (!value.getClass().isArray()) return new float[]{((Number) value).floatValue()};
        int length = Array.getLength(value);
        List<Float> values = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            for (float item : flatten(Array.get(value, i))) values.add(item);
        }
        float[] output = new float[values.size()];
        for (int i = 0; i < values.size(); i++) output[i] = values.get(i);
        return output;
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void close() {
        OrtSession current = session;
        if (current != null) {
            try {
                current.close();
            } catch (OrtException ignored) {
                log.debug("failed to close forced-alignment ONNX session", ignored);
            }
        }
    }

    private record ModelOutput(float[] probabilities, int frames, int vocabSize) { }

    private record Target(List<String> characters, List<List<Integer>> groups,
                          List<Integer> phoneIds, List<Integer> normalizedIndices) { }

    private record Weight(float[] data, int[] shape) { }

    private record WeightSpec(int offset, int[] shape) { }

    private static final class G2pm {
        private final Map<String, List<String>> cedict;
        private final Map<String, Integer> char2idx;
        private final List<String> idx2class;
        private final Map<String, Weight> weights;
        private final int hidden;
        private final int embedding;

        private G2pm(Map<String, List<String>> cedict, Map<String, Integer> char2idx,
                     List<String> idx2class, Map<String, Weight> weights) {
            this.cedict = cedict;
            this.char2idx = char2idx;
            this.idx2class = idx2class;
            this.weights = weights;
            this.hidden = weights.get("lstm.weight_hh_l0").shape()[1];
            this.embedding = weights.get("embedding.weight").shape()[1];
        }

        private List<String> convert(String sentence) {
            List<String> chars = sentence.codePoints().mapToObj(Character::toString).toList();
            int[] inputIds = new int[chars.size()];
            List<Integer> polyphonic = new ArrayList<>();
            List<String> pronunciations = new ArrayList<>();
            for (int index = 0; index < chars.size(); index++) {
                String character = chars.get(index);
                inputIds[index] = char2idx.getOrDefault(character, char2idx.getOrDefault(UNK, 1));
                List<String> values = cedict.get(character);
                if (values == null || values.isEmpty()) {
                    pronunciations.add(character);
                } else if (values.size() > 1) {
                    polyphonic.add(index);
                    pronunciations.add(SPLIT);
                } else {
                    pronunciations.add(values.get(0));
                }
            }
            if (!polyphonic.isEmpty()) {
                int bos = requiredIndex(BOS);
                int eos = requiredIndex(EOS);
                int[] padded = new int[inputIds.length + 2];
                padded[0] = bos;
                System.arraycopy(inputIds, 0, padded, 1, inputIds.length);
                padded[padded.length - 1] = eos;
                int[] positions = polyphonic.stream().mapToInt(index -> index + 1).toArray();
                int[] predictions = predict(padded, positions);
                for (int i = 0; i < polyphonic.size(); i++) {
                    pronunciations.set(polyphonic.get(i), idx2class.get(predictions[i]));
                }
            }
            StringBuilder joined = new StringBuilder();
            for (String pronunciation : pronunciations) {
                if (pronunciation.codePointCount(0, pronunciation.length()) == 1) {
                    joined.append(pronunciation);
                } else {
                    if (!joined.isEmpty() && joined.charAt(joined.length() - 1) != '|') joined.append('|');
                    joined.append(pronunciation).append('|');
                }
            }
            if (!joined.isEmpty() && joined.charAt(joined.length() - 1) == '|') joined.setLength(joined.length() - 1);
            return Arrays.stream(joined.toString().split("\\|", -1)).toList();
        }

        private int requiredIndex(String token) {
            Integer index = char2idx.get(token);
            if (index == null) throw new IllegalStateException("G2P asset is missing token " + token);
            return index;
        }

        private int[] predict(int[] inputIds, int[] targetIndices) {
            Float64State[] forward = new Float64State[inputIds.length];
            double[] h = new double[hidden], c = new double[hidden];
            for (int i = 0; i < inputIds.length; i++) {
                Float64State state = lstm(embedding(inputIds[i]), h, c, "");
                forward[i] = state;
                h = state.h();
                c = state.c();
            }
            Float64State[] reverse = new Float64State[inputIds.length];
            h = new double[hidden];
            c = new double[hidden];
            for (int i = 0; i < inputIds.length; i++) {
                Float64State state = lstm(embedding(inputIds[inputIds.length - 1 - i]), h, c, "_reverse");
                reverse[i] = state;
                h = state.h();
                c = state.c();
            }
            int[] result = new int[targetIndices.length];
            for (int i = 0; i < targetIndices.length; i++) {
                double[] combined = new double[hidden * 2];
                System.arraycopy(forward[targetIndices[i]].h(), 0, combined, 0, hidden);
                System.arraycopy(reverse[inputIds.length - 1 - targetIndices[i]].h(), 0, combined, hidden, hidden);
                result[i] = classify(combined);
            }
            return result;
        }

        private double[] embedding(int index) {
            Weight weight = weights.get("embedding.weight");
            int start = index * embedding;
            double[] result = new double[embedding];
            for (int i = 0; i < embedding; i++) result[i] = weight.data()[start + i];
            return result;
        }

        private Float64State lstm(double[] input, double[] previousH, double[] previousC, String direction) {
            int size = hidden * 4;
            double[] ih = affine(weights.get("lstm.weight_ih_l0" + direction),
                    weights.get("lstm.bias_ih_l0" + direction), input);
            double[] hh = affine(weights.get("lstm.weight_hh_l0" + direction),
                    weights.get("lstm.bias_hh_l0" + direction), previousH);
            double[] h = new double[hidden], c = new double[hidden];
            for (int i = 0; i < hidden; i++) {
                double gateI = sigmoid(ih[i] + hh[i]);
                double gateF = sigmoid(ih[hidden + i] + hh[hidden + i]);
                double gateG = Math.tanh(ih[hidden * 2 + i] + hh[hidden * 2 + i]);
                double gateO = sigmoid(ih[hidden * 3 + i] + hh[hidden * 3 + i]);
                c[i] = gateF * previousC[i] + gateI * gateG;
                h[i] = gateO * Math.tanh(c[i]);
            }
            return new Float64State(h, c);
        }

        private int classify(double[] input) {
            double[] hiddenValues = affine(weights.get("logit_layer.0.weight"),
                    weights.get("logit_layer.0.bias"), input);
            for (int i = 0; i < hiddenValues.length; i++) hiddenValues[i] = Math.max(0D, hiddenValues[i]);
            double[] logits = affine(weights.get("logit_layer.2.weight"),
                    weights.get("logit_layer.2.bias"), hiddenValues);
            int best = 0;
            for (int i = 1; i < logits.length; i++) if (logits[i] > logits[best]) best = i;
            return best;
        }

        private double[] affine(Weight matrix, Weight bias, double[] input) {
            int output = matrix.shape()[0], inputSize = matrix.shape()[1];
            double[] result = new double[output];
            for (int row = 0; row < output; row++) {
                double sum = bias.data()[row];
                int offset = row * inputSize;
                for (int col = 0; col < inputSize; col++) sum += matrix.data()[offset + col] * input[col];
                result[row] = sum;
            }
            return result;
        }

        private static double sigmoid(double value) {
            return 1D / (1D + Math.exp(-value));
        }
    }

    private record Float64State(double[] h, double[] c) { }

    private static final Set<String> CONSONANTS = Set.of(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "zh", "ch", "sh", "r", "z", "c", "s");

    private static final Map<String, String> TRANSFORM = Map.ofEntries(
            Map.entry("ju", "jv"), Map.entry("qu", "qv"), Map.entry("xu", "xv"), Map.entry("jue", "jve"),
            Map.entry("que", "qve"), Map.entry("xue", "xve"), Map.entry("quan", "qvan"), Map.entry("xuan", "xvan"),
            Map.entry("juan", "jvan"), Map.entry("qun", "qvn"), Map.entry("xun", "xvn"), Map.entry("jun", "jvn"),
            Map.entry("yuan", "van"), Map.entry("yue", "ve"), Map.entry("yun", "vn"), Map.entry("you", "iou"),
            Map.entry("yan", "ian"), Map.entry("yin", "in"), Map.entry("wa", "ua"), Map.entry("wo", "uo"),
            Map.entry("wai", "uai"), Map.entry("weng", "ueng"), Map.entry("wang", "uang"), Map.entry("wu", "u"),
            Map.entry("yu", "v"), Map.entry("yi", "i"), Map.entry("yo", "io"), Map.entry("ya", "ia"),
            Map.entry("ye", "ie"), Map.entry("yao", "iao"), Map.entry("yang", "iang"), Map.entry("ying", "ing"),
            Map.entry("yong", "iong"), Map.entry("yvan", "van"), Map.entry("yve", "ve"), Map.entry("yvn", "vn"),
            Map.entry("wei", "ui"), Map.entry("wan", "uan"), Map.entry("wen", "un"), Map.entry("yv", "v"),
            Map.entry("wuen", "un"), Map.entry("wuo", "uo"), Map.entry("wuang", "uang"), Map.entry("wuan", "uan"),
            Map.entry("wua", "ua"), Map.entry("wuai", "uai"), Map.entry("zhi", "zhiii"), Map.entry("chi", "chiii"),
            Map.entry("shi", "shiii"), Map.entry("zi", "zii"), Map.entry("ci", "cii"), Map.entry("si", "sii"));

    private static final Map<String, Object> RHYME = new HashMap<>();
    private static final Map<String, List<String>> ER = Map.of(
            "er1", List.of("e1", "rr"), "er2", List.of("e2", "rr"), "er3", List.of("e3", "rr"),
            "er4", List.of("e4", "rr"), "er5", List.of("e5", "rr"), "r5", List.of("e5", "rr"));

    static {
        RHYME.put("iu1", "iou1"); RHYME.put("iu2", "iou2"); RHYME.put("iu3", "iou3");
        RHYME.put("iu4", "iou4"); RHYME.put("iu5", "iou5"); RHYME.put("u:e1", "ve1");
        RHYME.put("u:e2", "ve2"); RHYME.put("u:e3", "ve3"); RHYME.put("u:e4", "ve4");
        RHYME.put("u:e5", "ve5"); RHYME.put("u:1", "v1"); RHYME.put("u:2", "v2");
        RHYME.put("u:3", "v3"); RHYME.put("u:4", "v4"); RHYME.put("u:5", "v5");
        RHYME.put("ueng1", List.of("u1", "eng1")); RHYME.put("ueng2", List.of("u2", "eng2"));
        RHYME.put("ueng3", List.of("u3", "eng3")); RHYME.put("ueng4", List.of("u4", "eng4"));
        RHYME.put("ueng5", List.of("u5", "eng5")); RHYME.put("io5", List.of("i5", "o5"));
        RHYME.put("io4", List.of("i4", "o4")); RHYME.put("io1", List.of("i1", "o1"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> rhyme(String syllable) {
        Object value = RHYME.get(syllable);
        return value instanceof List<?> list ? list.stream().map(String.class::cast).toList()
                : List.of(value == null ? syllable : (String) value);
    }

    private static List<String> separateSyllable(String syllable) {
        if (syllable.equals("ri4")) return List.of("r", "iii4");
        String body = syllable.substring(0, syllable.length() - 1);
        if (body.equals("ueng") || body.equals("io")) return rhyme(syllable);
        if (ER.containsKey(syllable)) return ER.get(syllable);
        if (body.length() >= 2 && CONSONANTS.contains(body.substring(0, 2))) {
            List<String> result = new ArrayList<>();
            result.add(body.substring(0, 2));
            result.addAll(rhyme(body.substring(2) + syllable.substring(syllable.length() - 1)));
            return result;
        }
        if (!body.isEmpty() && CONSONANTS.contains(body.substring(0, 1))) {
            List<String> result = new ArrayList<>();
            result.add(body.substring(0, 1));
            result.addAll(rhyme(body.substring(1) + syllable.substring(syllable.length() - 1)));
            return result;
        }
        return List.of(syllable);
    }

    /**
     * g2pM returns standard pinyin (for example wo3, yi3 and wen3), while
     * Charsiu's phone vocabulary uses the Mandarin phone inventory spelling
     * (uo3, i3 and un3). The transform table above contains that mapping but
     * was not applied before splitting syllables into model phones.
     */
    private static String canonicalSyllable(String syllable) {
        if (syllable == null || syllable.length() < 2) return syllable;
        char tone = syllable.charAt(syllable.length() - 1);
        if (tone < '1' || tone > '5') return syllable;
        String body = syllable.substring(0, syllable.length() - 1);
        return TRANSFORM.getOrDefault(body, body) + tone;
    }
}
