package app.senyap.notifications.internal;

import app.senyap.auth.DeviceApi;
import app.senyap.notifications.NotificationsApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Implementasi {@link NotificationsApi#push}: cari device lewat {@link DeviceApi}, kirim lewat {@link PushSender}. */
@Service
class PushService implements NotificationsApi {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final DeviceApi devices;
    private final PushSender sender;

    PushService(DeviceApi devices, PushSender sender) {
        this.devices = devices;
        this.sender = sender;
    }

    @Override
    public void push(List<Push> pushes) {
        if (pushes.isEmpty()) {
            return;
        }
        var targets = devices.findPushTargets(pushes.stream().map(Push::recipientUserId).distinct().toList());
        for (Push p : pushes) {
            for (var t : targets.getOrDefault(p.recipientUserId(), List.of())) {
                try {
                    sender.send(new PushSender.Message(t.deviceId(), t.platform(), t.pushToken(), p.type(), p.title(),
                            p.body(), p.collapseKey(), p.data()));
                } catch (RuntimeException e) {
                    log.warn("Push {} ke device {} gagal: {}", p.type(), t.deviceId(), e.getClass().getSimpleName());
                }
            }
        }
    }
}
