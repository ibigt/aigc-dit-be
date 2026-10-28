package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.caption.CaptionCue;
import cn.aigc.dit.generate.vad.SpeechSegment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** VAD-only caption allocator that never creates a cue over detected silence. */
public final class CaptionAligner {

    private CaptionAligner() {
    }

    public static List<SubtitleCue> align(String dialogue, List<SpeechSegment> segments) {
        List<String> phrases = phrases(dialogue);
        if (phrases.isEmpty() || segments == null || segments.isEmpty()) {
            return List.of();
        }
        // This is the most reliable first-version case: each punctuation
        // phrase is already a subtitle unit and each VAD island is one
        // spoken turn. Do not let a character-count estimate move a cue
        // across the user's detected speech boundaries.
        if (phrases.size() == segments.size()) {
            List<SubtitleCue> result = new ArrayList<>(phrases.size());
            for (int i = 0; i < phrases.size(); i++) {
                SpeechSegment segment = segments.get(i);
                result.add(new SubtitleCue(segment.startMs(), segment.endMs(), phrases.get(i)));
            }
            return List.copyOf(result);
        }

        if (phrases.size() > segments.size()) {
            // More phrases than VAD islands: keep each phrase in its assigned
            // speech island and divide that island by character weight.
            int[] phraseCounts = distributeByWeight(phrases.size(), segments.stream()
                    .map(SpeechSegment::durationMs).toList());
            List<SubtitleCue> result = new ArrayList<>();
            int phraseCursor = 0;
            for (int i = 0; i < segments.size(); i++) {
                int end = phraseCursor + phraseCounts[i];
                splitIntoSegment(result, phrases.subList(phraseCursor, end), segments.get(i));
                phraseCursor = end;
            }
            return List.copyOf(result);
        }

        // More VAD islands than phrases: assign adjacent islands to a phrase.
        // We still emit one cue per real island, so a silent gap never gets a
        // subtitle even though the logical phrase owns multiple islands.
        int[] segmentCounts = distributeByWeight(segments.size(), phrases.stream()
                .map(CaptionAligner::weight).toList());
        List<SubtitleCue> result = new ArrayList<>();
        int segmentCursor = 0;
        for (int i = 0; i < phrases.size(); i++) {
            int end = segmentCursor + segmentCounts[i];
            for (int j = segmentCursor; j < end; j++) {
                SpeechSegment segment = segments.get(j);
                result.add(new SubtitleCue(segment.startMs(), segment.endMs(), phrases.get(i)));
            }
            segmentCursor = end;
        }
        return List.copyOf(result);
    }

    public static List<CaptionCue> toCaptionCues(List<SubtitleCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        return cues.stream()
                .map(cue -> new CaptionCue(cue.startMs() / 1000D, cue.endMs() / 1000D, cue.text()))
                .toList();
    }

    static List<String> phrases(String dialogue) {
        List<String> lines = lines(dialogue);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (!StringUtils.hasText(line)) continue;
            StringBuilder current = new StringBuilder();
            int codePoints = 0;
            for (int offset = 0; offset < line.length();) {
                int codePoint = line.codePointAt(offset);
                int count = Character.charCount(codePoint);
                offset += count;
                current.appendCodePoint(codePoint);
                codePoints++;
                // Punctuation always splits; otherwise soft-max at 24 code points for long CJK runs.
                if (isBoundary(codePoint) || codePoints >= 24) {
                    addPhrase(result, current);
                    current.setLength(0);
                    codePoints = 0;
                }
            }
            addPhrase(result, current);
        }
        return result;
    }

    static List<String> lines(String dialogue) {
        return DramaStoryboardCompiler.captionLines(dialogue);
    }

    static List<SubtitleCue> estimated(String dialogue, long durationMs) {
        List<String> phrases = phrases(dialogue);
        if (phrases.isEmpty() || durationMs <= 0) return List.of();
        long totalWeight = phrases.stream().mapToLong(CaptionAligner::weight).sum();
        List<SubtitleCue> result = new ArrayList<>();
        long cursor = 0L;
        for (int i = 0; i < phrases.size(); i++) {
            long end = i == phrases.size() - 1 ? durationMs
                    : cursor + Math.max(1L, Math.round(durationMs * weight(phrases.get(i)) / (double) totalWeight));
            end = Math.min(durationMs, end);
            if (end > cursor) result.add(new SubtitleCue(cursor, end, phrases.get(i)));
            cursor = end;
        }
        return List.copyOf(result);
    }

    private static void splitIntoSegment(
            List<SubtitleCue> result, List<String> phrases, SpeechSegment segment) {
        long totalWeight = phrases.stream().mapToLong(CaptionAligner::weight).sum();
        long cursor = segment.startMs();
        for (int i = 0; i < phrases.size(); i++) {
            long end = i == phrases.size() - 1
                    ? segment.endMs()
                    : cursor + Math.max(1L, Math.round(
                    segment.durationMs() * weight(phrases.get(i)) / (double) totalWeight));
            end = Math.min(segment.endMs(), end);
            if (end > cursor) {
                result.add(new SubtitleCue(cursor, end, phrases.get(i)));
            }
            cursor = end;
        }
    }

    /** Distributes {@code total} items across buckets proportional to {@code weights}. */
    static int[] distributeByWeight(int total, List<Long> weights) {
        if (weights == null || weights.isEmpty()) {
            return new int[0];
        }
        if (total < weights.size()) {
            return distributeAllowingZeros(total, weights);
        }
        return distribute(total, weights);
    }

    private static int[] distribute(int total, List<Long> weights) {
        int[] result = new int[weights.size()];
        java.util.Arrays.fill(result, 1);
        int extra = total - result.length;
        if (extra <= 0 || weights.isEmpty()) {
            return result;
        }
        long totalWeight = weights.stream().mapToLong(value -> Math.max(1L, value)).sum();
        double[] fractions = new double[weights.size()];
        int assigned = 0;
        for (int i = 0; i < weights.size(); i++) {
            double exact = extra * Math.max(1L, weights.get(i)) / (double) totalWeight;
            int whole = (int) Math.floor(exact);
            result[i] += whole;
            assigned += whole;
            fractions[i] = exact - whole;
        }
        while (assigned < extra) {
            int best = 0;
            for (int i = 1; i < fractions.length; i++) {
                if (fractions[i] > fractions[best]) best = i;
            }
            result[best]++;
            fractions[best] = -1D;
            assigned++;
        }
        return result;
    }

    /** Largest-remainder allocation that may leave some buckets at zero. */
    private static int[] distributeAllowingZeros(int total, List<Long> weights) {
        int[] result = new int[weights.size()];
        if (total <= 0) {
            return result;
        }
        long totalWeight = weights.stream().mapToLong(value -> Math.max(1L, value)).sum();
        double[] fractions = new double[weights.size()];
        int assigned = 0;
        for (int i = 0; i < weights.size(); i++) {
            double exact = total * Math.max(1L, weights.get(i)) / (double) totalWeight;
            int whole = (int) Math.floor(exact);
            result[i] = whole;
            assigned += whole;
            fractions[i] = exact - whole;
        }
        while (assigned < total) {
            int best = 0;
            for (int i = 1; i < fractions.length; i++) {
                if (fractions[i] > fractions[best]) best = i;
            }
            result[best]++;
            fractions[best] = -1D;
            assigned++;
        }
        return result;
    }

    private static void addPhrase(List<String> result, StringBuilder value) {
        String text = value.toString().trim();
        if (!text.isEmpty()) {
            result.add(text);
        }
    }

    private static boolean isBoundary(int codePoint) {
        return switch (codePoint) {
            case '，', ',', '。', '.', '！', '!', '？', '?', '；', ';', '：', ':', '…' -> true;
            default -> false;
        };
    }

    private static long weight(String text) {
        long count = text == null ? 0 : text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !isBoundary(codePoint))
                .count();
        return Math.max(1L, count);
    }
}
