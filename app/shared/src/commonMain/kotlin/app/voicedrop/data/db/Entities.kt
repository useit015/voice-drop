package app.voicedrop.data.db

import androidx.room.Entity
import androidx.room.Index

/** Delivery state of an outgoing message. */
object Status {
    const val FAILED = -1
    const val PENDING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val READ = 3
    const val PLAYED = 4
}

@Entity(tableName = "msgs", primaryKeys = ["jid", "id"], indices = [Index("jid", "ts", "id")])
data class MessageEntity(
    val jid: String,
    val id: String,
    val fromMe: Boolean,
    val ts: Long,
    val sender: String? = null,
    val push: String? = null,
    /** text voice audio image video gif sticker document contact location poll revoked other */
    val kind: String,
    val text: String? = null,
    /** JSON of [app.voicedrop.engine.WireMeta]. */
    val meta: String? = null,
    /** base64 waE2E.Message, needed to download the attachment. */
    val raw: String? = null,
    val quoteId: String? = null,
    val quoteSender: String? = null,
    val quoteKind: String? = null,
    val quoteText: String? = null,
    val mentions: String? = null,
    val forwarded: Boolean = false,
    val status: Int = Status.SENT,
    val edited: Boolean = false,
    val starred: Boolean = false,
    /** Downloaded or locally-sent attachment. */
    val localPath: String? = null,
)

@Entity(tableName = "chats", primaryKeys = ["jid"])
data class ChatEntity(
    val jid: String,
    val name: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** ms; 0 = not muted, -1 = forever. */
    val mutedUntil: Long = 0,
    val unread: Int = 0,
    val markedUnread: Boolean = false,
    /** Incoming messages up to here have had read receipts sent. */
    val readUpTo: Long = 0,
    val size: Int = 0,
    val lastTs: Long = 0,
    val lastId: String? = null,
    val lastKind: String? = null,
    val lastText: String? = null,
    val lastFromMe: Boolean = false,
    val lastSender: String? = null,
    val lastStatus: Int = 0,
)

@Entity(tableName = "contacts", primaryKeys = ["jid"])
data class ContactEntity(
    val jid: String,
    val name: String? = null,
    val notify: String? = null,
    val lid: String? = null,
)

@Entity(tableName = "reactions", primaryKeys = ["jid", "msgId", "sender"], indices = [Index("jid", "msgId")])
data class ReactionEntity(
    val jid: String,
    val msgId: String,
    val sender: String,
    val emoji: String,
    val ts: Long,
)

@Entity(tableName = "avatars", primaryKeys = ["jid"])
data class AvatarEntity(
    val jid: String,
    val path: String? = null,
    val picId: String? = null,
    val checkedAt: Long = 0,
)

@Entity(tableName = "fav_sounds", primaryKeys = ["mp3"])
data class FavSoundEntity(val mp3: String, val title: String, val color: String? = null, val addedAt: Long)

@Entity(tableName = "recent_sounds", primaryKeys = ["mp3"])
data class RecentSoundEntity(val mp3: String, val title: String, val color: String? = null, val usedAt: Long)
