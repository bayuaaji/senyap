package app.senyap.chat.internal;

import app.senyap.chat.internal.ChatViews.Ack;
import app.senyap.chat.internal.ChatViews.Badge;
import app.senyap.chat.internal.ChatViews.ConversationView;
import app.senyap.chat.internal.ChatViews.MessageView;
import app.senyap.chat.internal.ChatViews.SyncItem;
import app.senyap.shared.error.ApiException;
import app.senyap.shared.error.ErrorCode;
import app.senyap.shared.id.Tsid;
import app.senyap.shared.security.AuthenticatedUser;
import app.senyap.shared.web.CursorPage;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST Bisik (functional spec FS-05 §5.4). Juga dipakai gateway realtime untuk {@code msg.send} dan receipts. */
@RestController
@ConditionalOnProperty(name = "senyap.api.enabled", havingValue = "true")
class ChatController {

    record StartRequest(@JsonProperty("origin_type") @NotNull @Pattern(regexp = "POST|REPLY") String originType,
                        @JsonProperty("origin_id") @NotNull String originId,
                        @JsonProperty("first_message") @NotNull @Size(min = 1, max = 500) String firstMessage,
                        @JsonProperty("client_msg_id") @NotNull UUID clientMsgId) {
    }

    record SendRequest(@JsonProperty("client_msg_id") @NotNull UUID clientMsgId,
                       @JsonProperty("type") String type,
                       @JsonProperty("body") @Size(max = 2000) String body,
                       @JsonProperty("media_id") String mediaId) {
    }

    record ReceiptsRequest(@JsonProperty("delivered_up_to") Long deliveredUpTo,
                           @JsonProperty("read_up_to") Long readUpTo) {
    }

    record SyncEntry(@JsonProperty("conversation_id") @NotNull String conversationId,
                     @JsonProperty("last_seq") long lastSeq) {
    }

    record SyncRequest(@JsonProperty("conversations") @Size(max = 500) List<@Valid SyncEntry> conversations) {
    }

    record SyncResponse(@JsonProperty("changed") List<SyncItem> changed) {
    }

    record MessagesPage(@JsonProperty("items") List<MessageView> items) {
    }

    private final ChatService chat;
    private final ChatQueries queries;

    ChatController(ChatService chat, ChatQueries queries) {
        this.chat = chat;
        this.queries = queries;
    }

    @PostMapping("/v1/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    ConversationView start(AuthenticatedUser user, @RequestBody @Validated StartRequest body) {
        long id = chat.start(user.userId(), body.originType(), id(body.originId()), body.firstMessage(),
                body.clientMsgId());
        return queries.detail(user.userId(), id);
    }

    @GetMapping("/v1/conversations")
    CursorPage<ConversationView> list(AuthenticatedUser user, @RequestParam(defaultValue = "inbox") String box,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        var b = switch (box) {
            case "inbox" -> ChatQueries.Box.INBOX;
            case "requests" -> ChatQueries.Box.REQUESTS;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "box harus inbox atau requests.");
        };
        int n = CursorPage.clampLimit(limit);
        return CursorPage.of(queries.list(user.userId(), b, cursor, n + 1), n, ChatQueries::cursorOf);
    }

    @GetMapping("/v1/conversations/badge")
    Badge badge(AuthenticatedUser user) {
        return queries.badge(user.userId());
    }

    @PostMapping("/v1/conversations/sync")
    SyncResponse sync(AuthenticatedUser user, @RequestBody @Validated SyncRequest body) {
        var known = new HashMap<Long, Long>();
        if (body.conversations() != null) {
            for (var e : body.conversations()) {
                known.put(id(e.conversationId()), e.lastSeq());
            }
        }
        return new SyncResponse(queries.sync(user.userId(), known, 500));
    }

    @GetMapping("/v1/conversations/{id}")
    ConversationView detail(AuthenticatedUser user, @PathVariable String id) {
        return queries.detail(user.userId(), id(id));
    }

    @PostMapping("/v1/conversations/{id}/accept")
    ConversationView accept(AuthenticatedUser user, @PathVariable String id) {
        chat.accept(user.userId(), id(id));
        return queries.detail(user.userId(), id(id));
    }

    @PostMapping("/v1/conversations/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(AuthenticatedUser user, @PathVariable String id) {
        chat.decline(user.userId(), id(id));
    }

    @PostMapping("/v1/conversations/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(AuthenticatedUser user, @PathVariable String id) {
        chat.leave(user.userId(), id(id));
    }

    @PostMapping("/v1/conversations/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(AuthenticatedUser user, @PathVariable String id) {
        chat.block(user.userId(), id(id));
    }

    @PostMapping("/v1/conversations/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hide(AuthenticatedUser user, @PathVariable String id) {
        chat.hide(user.userId(), id(id));
    }

    @GetMapping("/v1/conversations/{id}/messages")
    MessagesPage messages(AuthenticatedUser user, @PathVariable String id,
                          @RequestParam(name = "before_seq", required = false) Long beforeSeq,
                          @RequestParam(name = "after_seq", required = false) Long afterSeq,
                          @RequestParam(required = false) Integer limit) {
        if (beforeSeq != null && afterSeq != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Pilih before_seq atau after_seq.");
        }
        int n = limit == null ? 50 : Math.clamp(limit, 1, 200);
        return new MessagesPage(queries.messages(user.userId(), id(id), beforeSeq, afterSeq, n));
    }

    /** Fallback kirim (dan jalur gateway). Idempotent per {@code client_msg_id}: 201 baru, 200 kirim ulang. */
    @PostMapping("/v1/conversations/{id}/messages")
    ResponseEntity<Ack> send(AuthenticatedUser user, @PathVariable String id, @RequestBody @Validated SendRequest body) {
        Long mediaId = body.mediaId() == null ? null : id(body.mediaId());
        var sent = chat.send(user.userId(), id(id), body.clientMsgId(), body.type() == null ? "TEXT" : body.type(),
                body.body(), mediaId);
        return ResponseEntity.status(sent.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(sent.ack());
    }

    @DeleteMapping("/v1/conversations/{id}/messages/{seq}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retract(AuthenticatedUser user, @PathVariable String id, @PathVariable long seq) {
        chat.retract(user.userId(), id(id), seq);
    }

    @PostMapping("/v1/conversations/{id}/receipts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receipts(AuthenticatedUser user, @PathVariable String id, @RequestBody ReceiptsRequest body) {
        chat.receipts(user.userId(), id(id), body.deliveredUpTo(), body.readUpTo());
    }

    private static long id(String value) {
        try {
            return Tsid.parse(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
