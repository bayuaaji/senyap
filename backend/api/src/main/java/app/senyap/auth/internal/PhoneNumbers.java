package app.senyap.auth.internal;

import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import java.util.regex.Pattern;

/**
 * Normalisasi nomor HP ke E.164. Format lokal Indonesia diterima: {@code 0812…}, {@code 812…},
 * {@code 62812…}, {@code +62 812-…}. Nomor tidak pernah ditulis ke log atau pesan error.
 */
final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");

    private PhoneNumbers() {
    }

    static String normalize(String input) {
        if (input == null) {
            throw invalid();
        }
        String s = SEPARATORS.matcher(input.trim()).replaceAll("");
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        } else if (s.startsWith("0")) {
            s = "+62" + s.substring(1);
        } else if (s.startsWith("62")) {
            s = "+" + s;
        } else if (s.startsWith("8")) {
            s = "+62" + s;
        }
        if (!E164.matcher(s).matches()) {
            throw invalid();
        }
        if (s.startsWith("+62") && (!s.startsWith("+628") || s.length() < 12 || s.length() > 15)) {
            throw invalid(); // nomor seluler Indonesia: +62 8xx, 9–12 digit setelah kode negara
        }
        return s;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Nomor HP tidak valid.");
    }
}
