package org.owntracks.android.net.dawarich

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.preferences.Preferences
import timber.log.Timber

/**
 * Sends every published location to Dawarich as well, next to the configured endpoint. Points wait
 * in their own database until Dawarich takes them, so a server or network outage leaves no gap in
 * the history, and neither destination holds up the other. Dawarich drops points it already has.
 */
@Singleton
class DawarichUploader
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: Preferences,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:CoroutineScopes.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : Preferences.OnPreferenceChangeListener {
  init {
    preferences.registerOnPreferenceChangedListener(this)
  }

  @Entity
  data class PendingPoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val feature: String)

  @Dao
  interface PendingPointDao {
    @Insert fun insert(point: PendingPoint)

    @Query("SELECT * FROM PendingPoint ORDER BY id LIMIT :limit")
    fun oldest(limit: Int): List<PendingPoint>

    @Query("DELETE FROM PendingPoint WHERE id IN (:ids)") fun delete(ids: List<Long>)
  }

  @Database(entities = [PendingPoint::class], version = 1)
  abstract class PendingPointDatabase : RoomDatabase() {
    abstract fun pendingPointDao(): PendingPointDao
  }

  internal val dao by lazy {
    Room.databaseBuilder(context, PendingPointDatabase::class.java, "dawarich")
        .build()
        .pendingPointDao()
  }

  val isConfigured: Boolean
    get() = preferences.dawarichUrl.isNotBlank() && preferences.dawarichApiKey.isNotBlank()

  fun enqueue(message: MessageLocation) {
    if (!isConfigured) {
      return
    }
    val feature = Dawarich.toFeature(message, preferences.deviceId).toString()
    scope.launch(ioDispatcher) {
      dao.insert(PendingPoint(feature = feature))
      scheduleUpload()
    }
  }

  /**
   * Runs [DawarichUploadWorker] once the network is up. A point that arrives while an upload runs
   * gets its own run after it, so it never waits for the next location.
   */
  fun scheduleUpload() {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequest.Builder(DawarichUploadWorker::class.java)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    Timber.v("Dawarich upload scheduled")
  }

  /** New settings may unblock points that a wrong URL or API key held back. */
  override fun onPreferenceChanged(properties: Set<String>) {
    if (
        (Preferences::dawarichUrl.name in properties ||
            Preferences::dawarichApiKey.name in properties) && isConfigured
    ) {
      scheduleUpload()
    }
  }

  companion object {
    private const val UPLOAD_WORK_NAME = "DAWARICH_UPLOAD"
  }
}
