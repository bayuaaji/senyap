package app.senyap.safety.internal;

import app.senyap.safety.ContentType;
import app.senyap.safety.ImageScreening;
import app.senyap.safety.ImageVerdict;
import app.senyap.safety.ScreeningDecision;
import app.senyap.safety.TextScreening;
import app.senyap.safety.TextVerdict;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Moderasi otomatis FS-08, urutan: kata terlarang → pola doxxing (publik) → klasifikasi. Ambang: skor ≥
 * {@value #HOLD_AT} ditahan (HOLD), ≥ {@value #FLAG_AT} terbit + antrean (FLAG). Risiko menyakiti diri tidak
 * pernah menahan/menolak konten: hanya kartu bantuan + antrean P0.
 */
@Component
class ScreeningService {

    static final double HOLD_AT = 0.85;
    static final double FLAG_AT = 0.5;

    private final TextFilter filter;
    private final ModerationClassifier classifier;
    private final KnownImageHashes hashes;

    ScreeningService(TextFilter filter, ModerationClassifier classifier, KnownImageHashes hashes) {
        this.filter = filter;
        this.classifier = classifier;
        this.hashes = hashes;
    }

    TextVerdict screenText(TextScreening req) {
        String text = req.text() == null ? "" : req.text();
        String norm = TextNormalizer.normalize(text);
        var blocked = filter.blockedWord(norm);
        if (blocked.isPresent()) {
            return new TextVerdict(ScreeningDecision.REJECT, blocked.get(), false, List.of("blocked_word"), 2);
        }
        boolean publicText = req.kind() == ContentType.POST || req.kind() == ContentType.REPLY;
        if (publicText) {
            var dox = filter.doxxing(text);
            if (dox.isPresent()) {
                return new TextVerdict(ScreeningDecision.REJECT, dox.get(), false, List.of("doxxing"), 1);
            }
        }
        return decide(classifier.classifyText(text));
    }

    static TextVerdict decide(Map<String, Double> scores) {
        boolean selfHarm = scores.getOrDefault("self_harm", 0.0) >= FLAG_AT;
        double maxOther = scores.entrySet().stream().filter(e -> !e.getKey().equals("self_harm"))
                .mapToDouble(Map.Entry::getValue).max().orElse(0);
        var labels = scores.entrySet().stream().filter(e -> e.getValue() >= FLAG_AT).map(Map.Entry::getKey)
                .sorted().toList();
        ScreeningDecision d;
        if (maxOther >= HOLD_AT) {
            d = ScreeningDecision.HOLD;
        } else if (maxOther >= FLAG_AT || selfHarm) {
            d = ScreeningDecision.FLAG;
        } else {
            d = ScreeningDecision.ALLOW;
        }
        return new TextVerdict(d, null, selfHarm, labels, Priorities.highest(labels));
    }

    ImageVerdict screenImage(ImageScreening req) {
        if (hashes.matches(req.perceptualHashHex())) {
            return new ImageVerdict(ScreeningDecision.REJECT, ImageVerdict.KNOWN_HASH);
        }
        var scores = classifier.classifyImage(new ModerationClassifier.ImageSignals(req.skinRatio()));
        if (scores.getOrDefault("nudity", 0.0) >= HOLD_AT) {
            return new ImageVerdict(ScreeningDecision.REJECT, "NUDITY");
        }
        return new ImageVerdict(ScreeningDecision.ALLOW, null);
    }
}
