/// Model Bisik dari API (functional spec FS-05). Tidak ada user ID: lawan bicara hanya `public_id` + alias,
/// pengirim pesan hanya `me` / `them`.
library;

import '../profile/profile_models.dart';

class Person {
  const Person({required this.name, required this.color, this.publicId, this.tag});

  final String? publicId;
  final String name;
  final String? tag;
  final String color;

  String get label => tag == null ? name : '$name #$tag';

  Alias get asAlias => Alias(name: name, tag: tag ?? '', color: color);

  factory Person.fromJson(Map<String, dynamic> j) => Person(
        publicId: j['public_id'] as String?,
        name: j['name'] as String? ?? '[dihapus]',
        tag: j['tag'] as String?,
        color: j['color'] as String? ?? '#9E9E9E',
      );
}

enum ConversationStatus { requested, active, closed, declined }

ConversationStatus _status(String s) => switch (s) {
      'REQUESTED' => ConversationStatus.requested,
      'ACTIVE' => ConversationStatus.active,
      'DECLINED' => ConversationStatus.declined,
      _ => ConversationStatus.closed,
    };

class Conversation {
  const Conversation({
    required this.id,
    required this.status,
    required this.isInitiator,
    required this.peer,
    required this.originType,
    required this.originId,
    required this.lastSeq,
    required this.unreadCount,
    required this.myReadUpTo,
    required this.peerDeliveredUpTo,
    required this.peerReadUpTo,
    this.excerpt,
    this.lastMessage,
    this.lastMessageAt,
  });

  final String id;
  final ConversationStatus status;
  final bool isInitiator;
  final Person peer;
  final String originType;
  final String originId;
  final String? excerpt;
  final int lastSeq;
  final ChatMessage? lastMessage;
  final int unreadCount;
  final int myReadUpTo;
  final int peerDeliveredUpTo;
  final int peerReadUpTo;
  final DateTime? lastMessageAt;

  /// Request masuk yang menunggu jawaban saya.
  bool get awaitingMyAnswer => status == ConversationStatus.requested && !isInitiator;

  factory Conversation.fromJson(Map<String, dynamic> j) {
    final origin = j['origin'] as Map<String, dynamic>;
    final last = j['last_message'];
    return Conversation(
      id: j['id'] as String,
      status: _status(j['status'] as String),
      isInitiator: j['role'] == 'initiator',
      peer: Person.fromJson(j['peer'] as Map<String, dynamic>),
      originType: origin['type'] as String,
      originId: origin['id'] as String,
      excerpt: origin['excerpt'] as String?,
      lastSeq: (j['last_seq'] as num).toInt(),
      lastMessage: last is Map<String, dynamic> ? ChatMessage.fromJson(last) : null,
      unreadCount: (j['unread_count'] as num?)?.toInt() ?? 0,
      myReadUpTo: (j['my_read_up_to'] as num?)?.toInt() ?? 0,
      peerDeliveredUpTo: (j['peer_delivered_up_to'] as num?)?.toInt() ?? 0,
      peerReadUpTo: (j['peer_read_up_to'] as num?)?.toInt() ?? 0,
      lastMessageAt: j['last_message_at'] == null ? null : DateTime.parse(j['last_message_at'] as String),
    );
  }
}

/// Status kirim pesan sendiri: ⏱ mengirim → ✓ tersimpan → ✓✓ sampai → ✓✓ biru dibaca.
enum Delivery { sending, failed, sent, delivered, read }

class ChatMessage {
  const ChatMessage({
    required this.conversationId,
    required this.mine,
    required this.type,
    required this.createdAt,
    this.seq,
    this.clientMsgId,
    this.body,
    this.mediaUrl,
    this.retracted = false,
    this.failed = false,
  });

  final String conversationId;

  /// `null` = belum tersimpan di server (masih di antrean lokal).
  final int? seq;
  final String? clientMsgId;
  final bool mine;
  final String type;
  final String? body;
  final String? mediaUrl;
  final bool retracted;
  final bool failed;
  final DateTime createdAt;

  bool get isSystem => type == 'SYSTEM';
  bool get isImage => type == 'IMAGE';

  Delivery delivery({required int peerDeliveredUpTo, required int peerReadUpTo}) {
    if (failed) return Delivery.failed;
    final s = seq;
    if (s == null) return Delivery.sending;
    if (s <= peerReadUpTo) return Delivery.read;
    if (s <= peerDeliveredUpTo) return Delivery.delivered;
    return Delivery.sent;
  }

  ChatMessage copyWith({int? seq, bool? retracted}) => ChatMessage(
        conversationId: conversationId,
        seq: seq ?? this.seq,
        clientMsgId: clientMsgId,
        mine: mine,
        type: type,
        body: (retracted ?? this.retracted) ? null : body,
        mediaUrl: (retracted ?? this.retracted) ? null : mediaUrl,
        retracted: retracted ?? this.retracted,
        failed: failed,
        createdAt: createdAt,
      );

  factory ChatMessage.fromJson(Map<String, dynamic> j) {
    final media = j['media'];
    return ChatMessage(
      conversationId: j['conversation_id'] as String,
      seq: (j['seq'] as num?)?.toInt(),
      clientMsgId: j['client_msg_id'] as String?,
      mine: j['sender'] == 'me',
      type: j['type'] as String? ?? 'TEXT',
      body: j['body'] as String?,
      mediaUrl: media is Map ? media['url'] as String? : null,
      retracted: j['retracted'] == true,
      createdAt: DateTime.parse(j['created_at'] as String),
    );
  }

  Map<String, dynamic> toJson() => {
        'conversation_id': conversationId,
        'seq': ?seq,
        'client_msg_id': ?clientMsgId,
        'sender': mine ? 'me' : 'them',
        'type': type,
        'body': ?body,
        if (mediaUrl != null) 'media': {'url': mediaUrl},
        'retracted': retracted,
        'created_at': createdAt.toUtc().toIso8601String(),
      };
}

class ChatBadge {
  const ChatBadge({required this.unreadMessages, required this.requests});

  final int unreadMessages;
  final int requests;
}
