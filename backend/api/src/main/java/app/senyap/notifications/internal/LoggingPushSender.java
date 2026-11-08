package app.senyap.notifications.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Push mock (lokal/test, tanpa kredensial FCM): mencatat push terakhir di memori dan menulis log tanpa
 * token maupun isi. Dipakai integration test untuk membuktikan penerima offline mendapat push.
 * Ganti dengan implementasi FCM sebelum produksi (Fase 5).
 */
@Component
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);
    private static final int KEEP = 500;

    /** Yang tercatat: tanpa token. */
    public record Sent(long deviceId, String type, String title, String body, String collapseKey,
                       java.util.Map<String, String> data) {
    }

    private final Deque<Sent> recent = new ArrayDeque<>();

    @Override
    public boolean send(Message m) {
        log.info("Push mock type={} device={} collapse={}", m.type(), m.deviceId(), m.collapseKey());
        synchronized (recent) {
            recent.addLast(new Sent(m.deviceId(), m.type(), m.title(), m.body(), m.collapseKey(), m.data()));
            while (recent.size() > KEEP) {
                recent.removeFirst();
            }
        }
        return true;
    }

    public List<Sent> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }
}
