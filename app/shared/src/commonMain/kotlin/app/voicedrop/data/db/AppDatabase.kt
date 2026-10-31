package app.voicedrop.data.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        MessageEntity::class, ChatEntity::class, ContactEntity::class, ReactionEntity::class,
        AvatarEntity::class, FavSoundEntity::class, RecentSoundEntity::class,
    ],
    version = 1,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun chats(): ChatDao
    abstract fun contacts(): ContactDao
    abstract fun reactions(): ReactionDao
    abstract fun avatars(): AvatarDao
    abstract fun sounds(): SoundDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

@Dao
interface MessageDao {
    /** Newest `limit` messages, newest first (the chat list is drawn bottom-up). */
    @Query("SELECT * FROM msgs WHERE jid = :jid ORDER BY ts DESC, id DESC LIMIT :limit")
    fun latest(jid: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM msgs WHERE jid = :jid AND id = :id")
    suspend fun get(jid: String, id: String): MessageEntity?

    @Query("SELECT * FROM msgs WHERE jid = :jid AND kind != 'revoked' ORDER BY ts ASC, id ASC LIMIT 1")
    suspend fun oldest(jid: String): MessageEntity?

    @Query("SELECT * FROM msgs WHERE jid = :jid AND kind != 'revoked' ORDER BY ts DESC, id DESC LIMIT 1")
    suspend fun newest(jid: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM msgs WHERE jid = :jid")
    suspend fun count(jid: String): Int

    @Query("SELECT * FROM msgs WHERE jid = :jid AND fromMe = 0 AND ts > :after ORDER BY ts ASC LIMIT 200")
    suspend fun incomingAfter(jid: String, after: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<MessageEntity>): List<Long>

    @Upsert
    suspend fun upsert(row: MessageEntity)

    @Query("UPDATE msgs SET kind = 'revoked', text = NULL, meta = NULL, raw = NULL, localPath = NULL WHERE jid = :jid AND id = :id")
    suspend fun revoke(jid: String, id: String)

    @Query("DELETE FROM msgs WHERE jid = :jid AND id = :id")
    suspend fun delete(jid: String, id: String)

    @Query("UPDATE msgs SET text = :text, edited = 1 WHERE jid = :jid AND id = :id")
    suspend fun edit(jid: String, id: String, text: String)

    @Query("UPDATE msgs SET status = :status WHERE jid = :jid AND id = :id")
    suspend fun setStatus(jid: String, id: String, status: Int)

    /** Receipts only move forward (sent → delivered → read → played). */
    @Query("UPDATE msgs SET status = :status WHERE jid = :jid AND fromMe = 1 AND id IN (:ids) AND status < :status AND status >= 0")
    suspend fun raiseStatus(jid: String, ids: List<String>, status: Int)

    @Query("UPDATE msgs SET localPath = :path WHERE jid = :jid AND id = :id")
    suspend fun setLocalPath(jid: String, id: String, path: String?)

    @Query("UPDATE msgs SET raw = :raw WHERE jid = :jid AND id = :id")
    suspend fun setRaw(jid: String, id: String, raw: String)

    @Query("UPDATE msgs SET starred = :starred WHERE jid = :jid AND id = :id")
    suspend fun setStarred(jid: String, id: String, starred: Boolean)

    @Query("SELECT * FROM msgs WHERE kind = 'text' AND text LIKE '%' || :q || '%' ORDER BY ts DESC LIMIT 50")
    suspend fun search(q: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM msgs")
    fun total(): Flow<Int>

    @Query("DELETE FROM msgs")
    suspend fun clear()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE lastTs > 0 OR pinned = 1 ORDER BY pinned DESC, lastTs DESC")
    fun all(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE jid = :jid")
    fun observe(jid: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE jid = :jid")
    suspend fun get(jid: String): ChatEntity?

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Upsert
    suspend fun upsertAll(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE jid IN (:jids)")
    suspend fun getMany(jids: List<String>): List<ChatEntity>

    @Query("DELETE FROM chats")
    suspend fun clear()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    fun all(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE jid IN (:jids)")
    suspend fun getMany(jids: List<String>): List<ContactEntity>

    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts")
    suspend fun clear()
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE jid = :jid")
    fun forChat(jid: String): Flow<List<ReactionEntity>>

    @Upsert
    suspend fun upsert(r: ReactionEntity)

    @Query("DELETE FROM reactions WHERE jid = :jid AND msgId = :msgId AND sender = :sender")
    suspend fun delete(jid: String, msgId: String, sender: String)

    @Query("DELETE FROM reactions")
    suspend fun clear()
}

@Dao
interface AvatarDao {
    @Query("SELECT * FROM avatars WHERE jid = :jid")
    fun observe(jid: String): Flow<AvatarEntity?>

    @Query("SELECT * FROM avatars WHERE jid = :jid")
    suspend fun get(jid: String): AvatarEntity?

    @Upsert
    suspend fun upsert(a: AvatarEntity)

    @Query("UPDATE avatars SET checkedAt = 0 WHERE jid = :jid")
    suspend fun invalidate(jid: String)

    @Query("DELETE FROM avatars")
    suspend fun clear()
}

@Dao
interface SoundDao {
    @Query("SELECT * FROM fav_sounds ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavSoundEntity>>

    @Upsert
    suspend fun addFavorite(s: FavSoundEntity)

    @Query("DELETE FROM fav_sounds WHERE mp3 = :mp3")
    suspend fun removeFavorite(mp3: String)

    @Query("SELECT * FROM recent_sounds ORDER BY usedAt DESC LIMIT 60")
    fun recent(): Flow<List<RecentSoundEntity>>

    @Upsert
    suspend fun touchRecent(s: RecentSoundEntity)
}
