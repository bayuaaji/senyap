package app.senyap.chat.internal;

import app.senyap.shared.outbox.OutboxRelay;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Relay outbox chat dengan latensi rendah (target p95 kirim→terima < 500 ms). Satu thread serial (urutan
 * event per percakapan terjaga), bangun setiap {@code poll-interval} atau segera setelah {@link #kick()}
 * (dipanggil {@link ChatService} setelah commit bila api + worker satu proses). Menggantikan relay chat di
 * scheduler bersama yang hanya jalan tiap 500 ms.
 */
class ChatOutboxPump implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatOutboxPump.class);

    private final OutboxRelay relay;
    private final Duration pollInterval;
    private final Semaphore signal = new Semaphore(0);
    private volatile boolean running = true;
    private Thread thread;

    ChatOutboxPump(OutboxRelay relay, Duration pollInterval) {
        this.relay = relay;
        this.pollInterval = pollInterval;
    }

    void kick() {
        if (signal.availablePermits() == 0) {
            signal.release();
        }
    }

    @Override
    public void afterPropertiesSet() {
        thread = Thread.ofPlatform().name("chat-outbox-pump").daemon(true).start(this::loop);
    }

    private void loop() {
        while (running) {
            try {
                signal.tryAcquire(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
                signal.drainPermits();
                while (running && relay.relayOnce() > 0) {
                    // kirim terus selama masih ada antrean
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Relay outbox chat error: {}", e.getClass().getSimpleName());
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread.join(2000);
        }
    }
}
