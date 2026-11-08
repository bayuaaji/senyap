package app.senyap.chat.internal;

import app.senyap.contracts.ws.FrameType;
import app.senyap.contracts.ws.WsFrame;
import app.senyap.identity.IdentityApi;
import app.senyap.media.DmMediaApi;
import app.senyap.notifications.NotificationsApi;
import app.senyap.notifications.NotificationsApi.Push;
import app.senyap.shared.db.ModuleDatabase;
import app.senyap.shared.id.Tsid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery router (build guide §1.2 langkah 6): konsumsi {@code chat.events} → frame ke node gateway penerima
 * lewat Redis bila online, push ({@link NotificationsApi}) bila offline. Juga consumer {@code user.blocked}
 * (safety) yang menutup percakapan. Idempotent per {@code event_id} ({@code chat.consumed_events}).
 */
@Component
@ConditionalOnProperty(name = "senyap.worker.enabled", havingValue = "true")
class DeliveryRouter {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRouter.class);
    static final String CONSUMER_DELIVERY = "chat-delivery";
    static final String CONSUMER_SAFETY = "chat-safety";

    private final ModuleDatabase db;
    private final JsonMapper json;
    private final RealtimePublisher realtime;
    private final NotificationsApi notifications;
    private final IdentityApi identity;
    private final DmMediaApi media;
    private final ChatService chat;

    DeliveryRouter(@Qualifier("chatDatabase") ModuleDatabase db, JsonMapper json, RealtimePublisher realtime,
                   NotificationsApi notifications, IdentityApi identity, DmMediaApi media, ChatService chat) {
        this.db = db;
        this.json = json;
        this.realtime = realtime;
        this.notifications = notifications;
        this.identity = identity;
        this.media = media;
        this.chat = chat;
    }

    @KafkaListener(id = CONSUMER_DELIVERY, groupId = CONSUMER_DELIVERY, topics = "${senyap.chat.topic:chat.events}")
    void onChatEvent(String value) {
        JsonNode event = json.readTree(value);
        UUID eventId = UUID.fromString(event.get("event_id").asString());
        if (consumed(CONSUMER_DELIVERY, eventId)) {
            return;
        }
        JsonNode p = event.get("payload");
        long conversationId = p.get("conversation_id").asLong();
        switch (event.get("type").asString()) {
            case "message.created" -> messageCreated(conversationId, p.get("seq").asLong());
            case "message.retracted" -> toMembers(conversationId, null,
                    WsFrame.of(FrameType.MSG_RETRACTED, null, Map.of("conversation_id", Tsid.toString(conversationId),
                            "seq", p.get("seq").asLong())));
            case "conversation.requested" -> convUpdated(conversationId, "REQUESTED",
                    List.of(p.get("recipient_id").asLong()), "DM_REQUEST", "Seseorang ingin berbisik denganmu");
            case "conversation.accepted" -> convUpdated(conversationId, "ACTIVE",
                    List.of(p.get("initiator_id").asLong(), p.get("recipient_id").asLong()), "DM_ACCEPTED",
                    "Bisikanmu diterima");
            case "conversation.declined" -> convUpdated(conversationId, "DECLINED",
                    List.of(p.get("initiator_id").asLong()), null, null);
            case "conversation.closed" -> convUpdated(conversationId, "CLOSED",
                    List.of(p.get("initiator_id").asLong(), p.get("recipient_id").asLong()), null, null);
            default -> { }
        }
        markConsumed(CONSUMER_DELIVERY, eventId);
    }

    @KafkaListener(id = CONSUMER_SAFETY, groupId = CONSUMER_SAFETY, topics = "${senyap.safety.topic:safety.events}")
    void onSafetyEvent(String value) {
        JsonNode event = json.readTree(value);
        if (!"user.blocked".equals(event.get("type").asString())) {
            return;
        }
        UUID eventId = UUID.fromString(event.get("event_id").asString());
        if (consumed(CONSUMER_SAFETY, eventId)) {
            return;
        }
        JsonNode p = event.get("payload");
        chat.closeBetween(p.get("blocker_id").asLong(), p.get("blocked_id").asLong());
        markConsumed(CONSUMER_SAFETY, eventId);
    }

    private void messageCreated(long conversationId, long seq) {
        var msg = db.jdbc().sql("SELECT " + ChatRows.Message.COLUMNS + " FROM messages WHERE conversation_id = :c AND seq = :s")
                .param("c", conversationId).param("s", seq).query((rs, n) -> ChatRows.Message.map(rs)).optional();
        if (msg.isEmpty()) {
            return;
        }
        var m = msg.get();
        var image = m.mediaId() == null ? null : media.findDmImages(List.of(m.mediaId())).get(m.mediaId());
        var members = activeMembers(conversationId);
        Set<Long> offline = realtime.publish(members, userId -> WsFrame.of(FrameType.MSG_NEW, null,
                ChatViews.MessageView.of(m, userId, image).asFrameData()));
        if ("SYSTEM".equals(m.type())) {
            return;
        }
        var pushes = new ArrayList<Push>();
        String senderName = null;
        for (Long userId : members) {
            if (userId == m.senderId() || !offline.contains(userId)) {
                continue;
            }
            if (senderName == null) {
                var who = identity.resolveIdentities(List.of(m.senderId())).get(m.senderId());
                senderName = who == null ? "seseorang" : who.name();
            }
            pushes.add(push(userId, "DM_MESSAGE", "Pesan baru dari " + senderName, conversationId));
        }
        notifications.push(pushes);
    }

    private void convUpdated(long conversationId, String status, List<Long> targets, String pushType, String pushText) {
        var frame = WsFrame.of(FrameType.CONV_UPDATED, null,
                Map.of("conversation_id", Tsid.toString(conversationId), "status", status));
        Set<Long> offline = realtime.publish(targets, u -> frame);
        if (pushType == null) {
            return;
        }
        var pushes = new ArrayList<Push>();
        // DM_ACCEPTED hanya untuk pengirim request (target pertama); DM_REQUEST untuk penerima.
        Long who = targets.getFirst();
        if (offline.contains(who)) {
            pushes.add(push(who, pushType, pushText, conversationId));
        }
        notifications.push(pushes);
    }

    private void toMembers(long conversationId, Long except, WsFrame frame) {
        var members = activeMembers(conversationId);
        if (except != null) {
            members.remove(except);
        }
        realtime.publish(members, u -> frame);
    }

    private List<Long> activeMembers(long conversationId) {
        return new ArrayList<>(db.jdbc().sql("""
                        SELECT user_id FROM conversation_members WHERE conversation_id = :c AND left_at IS NULL""")
                .param("c", conversationId).query(Long.class).list());
    }

    private static Push push(long userId, String type, String text, long conversationId) {
        String conv = Tsid.toString(conversationId);
        return new Push(userId, type, "Senyap", text, "conv-" + conv, Map.of("type", type, "conversation_id", conv));
    }

    private boolean consumed(String consumer, UUID eventId) {
        return db.jdbc().sql("SELECT EXISTS (SELECT 1 FROM consumed_events WHERE consumer = :c AND event_id = :e)")
                .param("c", consumer).param("e", eventId).query(Boolean.class).single();
    }

    private void markConsumed(String consumer, UUID eventId) {
        db.jdbc().sql("INSERT INTO consumed_events (consumer, event_id) VALUES (:c, :e) ON CONFLICT DO NOTHING")
                .param("c", consumer).param("e", eventId).update();
    }
}
