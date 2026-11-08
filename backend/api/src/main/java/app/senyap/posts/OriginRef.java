package app.senyap.posts;

/** Asal Bisik yang diminta client: post atau balasan, dengan ID internalnya. */
public record OriginRef(OriginType type, long id) {
}
