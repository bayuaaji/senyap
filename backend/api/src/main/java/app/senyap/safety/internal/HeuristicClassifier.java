package app.senyap.safety.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pengganti sementara API moderasi: frasa dari {@code classifier-terms.txt} + sinyal spam (banyak tautan,
 * karakter berulang) + rasio warna kulit untuk gambar. <b>Bukan moderasi yang andal</b>; hanya supaya alur
 * antrean P0/P1/P2 bisa dibangun dan diuji sebelum penyedia dipilih.
 */
@Component
class HeuristicClassifier implements ModerationClassifier {

    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|id|net|xyz|link|ly)\\b)");
    private static final Pattern REPEAT = Pattern.compile("(.)\\1{14,}");

    private record Term(String label, double score, String phrase) {
    }

    private final List<Term> terms = new ArrayList<>();
    private final double nudityRatio;

    HeuristicClassifier(SafetyProperties props) {
        for (String line : TermFiles.lines(props.classifierTermsFile())) {
            String[] p = line.split("\\|", 3);
            if (p.length == 3) {
                terms.add(new Term(p[0].strip(), Double.parseDouble(p[1].strip()), TextNormalizer.normalize(p[2])));
            }
        }
        this.nudityRatio = props.nudityRejectSkinRatio();
    }

    @Override
    public Map<String, Double> classifyText(String text) {
        String norm = TextNormalizer.normalize(text);
        var scores = new HashMap<String, Double>();
        for (Term t : terms) {
            if (TextNormalizer.contains(norm, t.phrase())) {
                scores.merge(t.label(), t.score(), Math::max);
            }
        }
        var urls = URL.matcher(text);
        int n = 0;
        while (urls.find()) {
            n++;
        }
        if (n >= 3 || REPEAT.matcher(text).find()) {
            scores.merge("spam", 0.6, Math::max);
        }
        return scores;
    }

    @Override
    public Map<String, Double> classifyImage(ImageSignals signals) {
        // Rasio kulit sangat tinggi ≈ foto tubuh close-up. Ambang konservatif: hanya kasus ekstrem.
        if (signals.skinRatio() >= nudityRatio) {
            return Map.of("nudity", 0.9);
        }
        return Map.of();
    }
}
