package app.senyap.contracts.ws;

/** Nilai {@code t} yang sah (functional spec FS-05 §5.5). Protokol versi 1. */
public final class FrameType {

    public static final int PROTOCOL_VERSION = 1;

    // client → server
    public static final String MSG_SEND = "msg.send";
    public static final String MSG_DELIVERED = "msg.delivered";
    public static final String MSG_READ = "msg.read";
    public static final String TYPING = "typing";
    public static final String PING = "ping";

    // server → client
    public static final String MSG_ACK = "msg.ack";
    public static final String MSG_NEW = "msg.new";
    public static final String MSG_STATUS = "msg.status";
    public static final String MSG_RETRACTED = "msg.retracted";
    public static final String CONV_UPDATED = "conv.updated";
    public static final String ERROR = "error";
    public static final String PONG = "pong";

    /** Kode penutupan WebSocket saat access token tidak berlaku lagi: client refresh token lalu sambung ulang. */
    public static final int CLOSE_UNAUTHENTICATED = 4401;

    /**
     * Node gateway sedang berhenti (deploy/scale down): RFC 6455 "Service Restart". Client sambung ulang segera
     * (jeda acak pendek, tanpa backoff panjang) ke node lain lewat load balancer, lalu sinkron {@code last_seq}.
     */
    public static final int CLOSE_SERVICE_RESTART = 1012;

    private FrameType() {
    }
}
