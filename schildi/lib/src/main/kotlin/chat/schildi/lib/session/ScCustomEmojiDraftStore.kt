package chat.schildi.lib.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

private val Context.scCustomEmojiDraftDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "sloppychat-custom-emoji-drafts")

private fun roomKey(roomId: String) = stringPreferencesKey("room_$roomId")

/**
 * Holds the shortcode → MXC URL mapping for custom emojis the user has selected
 * in a room's composer but not yet sent.
 *
 * Survives:
 *  - rotation, navigation away+back, presenter recreation (in-memory cache at session scope)
 *  - process death (write-through to a Preferences DataStore)
 *
 * Read API is synchronous and returns the in-memory snapshot. The cache is hydrated
 * asynchronously from disk on first access per room — call [awaitHydration] before
 * any path that depends on the persisted entries (notably the send-time substitution).
 *
 * Room ids are kept as raw strings so this module doesn't pull libraries:matrix:api
 * (architecture already depends on schildi/lib, so the reverse edge would cycle).
 */
@SingleIn(AppScope::class)
@Inject
class ScCustomEmojiDraftStore(
    @ApplicationContext context: Context,
) {
    private val store = context.scCustomEmojiDraftDataStore

    // Self-owned scope for write-through. The store outlives any one session/room,
    // so we don't tie writes to a presenter coroutine scope.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val hydration = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    // One write lock per room — without this, a put followed by a clear can
    // land in any order on the IO dispatcher, which lets a stale put resurrect
    // entries the user just cleared.
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(roomId: String): Mutex =
        writeLocks.getOrPut(roomId) { Mutex() }

    fun snapshot(roomId: String): Map<String, String> {
        ensureHydrationStarted(roomId)
        return cache[roomId]?.toMap() ?: emptyMap()
    }

    fun put(roomId: String, shortcode: String, mxcUrl: String) {
        ensureHydrationStarted(roomId)
        val map = cache.getOrPut(roomId) { ConcurrentHashMap() }
        map[shortcode] = mxcUrl
        val toPersist = map.toMap()
        ioScope.launch {
            lockFor(roomId).withLock { persist(roomId, toPersist) }
        }
    }

    fun clear(roomId: String) {
        cache[roomId]?.clear()
        ioScope.launch {
            lockFor(roomId).withLock { persist(roomId, emptyMap()) }
        }
    }

    /**
     * Suspend until the on-disk state for [roomId] has been merged into the in-memory
     * cache. Call this before the first read that must reflect persisted state, e.g.
     * before the send-time HTML substitution in the composer.
     */
    suspend fun awaitHydration(roomId: String) {
        ensureHydrationStarted(roomId).await()
    }

    private fun ensureHydrationStarted(roomId: String): CompletableDeferred<Unit> {
        return hydration.getOrPut(roomId) {
            CompletableDeferred<Unit>().also { signal ->
                ioScope.launch {
                    runCatching {
                        val raw = store.data.firstOrNull()?.get(roomKey(roomId))
                        val saved = raw?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty()
                        if (saved.isNotEmpty()) {
                            val map = cache.getOrPut(roomId) { ConcurrentHashMap() }
                            // Don't clobber any in-flight selections that landed before hydration
                            saved.forEach { (k, v) -> map.putIfAbsent(k, v) }
                        }
                    }.onFailure { Timber.tag("ScCustomEmoji").w(it, "Hydration failed for $roomId") }
                    signal.complete(Unit)
                }
            }
        }
    }

    private suspend fun persist(roomId: String, snapshot: Map<String, String>) {
        runCatching {
            store.edit { prefs ->
                if (snapshot.isEmpty()) {
                    prefs.remove(roomKey(roomId))
                } else {
                    prefs[roomKey(roomId)] = Json.encodeToString(snapshot)
                }
            }
        }.onFailure { Timber.tag("ScCustomEmoji").w(it, "Persist failed for $roomId") }
    }

    @Suppress("unused") // For tests / future use
    internal fun blockingHydrate(roomId: String) = runBlocking { awaitHydration(roomId) }
}
