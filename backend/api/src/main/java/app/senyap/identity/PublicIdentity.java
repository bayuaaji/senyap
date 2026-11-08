package app.senyap.identity;

import java.util.UUID;

/** Yang boleh dilihat user lain: handle opaque + alias. Contoh: "Kelinci Jingga" #4821 #F28C38. */
public record PublicIdentity(UUID publicId, String name, String tag, String color) {
}
