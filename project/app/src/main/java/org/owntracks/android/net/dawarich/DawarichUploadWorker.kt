package org.owntracks.android.net.dawarich

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.net.http.HttpMessageProcessorEndpoint
import org.owntracks.android.preferences.Preferences
import timber.log.Timber

/** Uploads the pending Dawarich points in batches until none are left. */
@HiltWorker
class DawarichUploadWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val preferences: Preferences,
    private val uploader: DawarichUploader,
    @param:CoroutineScopes.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(context, workerParams) {
  override suspend fun doWork(): Result =
      withContext(ioDispatcher) { if (uploader.isConfigured) uploadAll() else Result.success() }

  private fun uploadAll(): Result {
    val url = Dawarich.pointsUrl(preferences.dawarichUrl)
    while (true) {
      val batch = uploader.dao.oldest(Dawarich.BATCH_SIZE)
      if (batch.isEmpty()) {
        return Result.success()
      }
      val request =
          Request.Builder()
              .url(url)
              .header("Authorization", "Bearer ${preferences.dawarichApiKey.trim()}")
              .header(
                  HttpMessageProcessorEndpoint.HEADER_USERAGENT,
                  HttpMessageProcessorEndpoint.USERAGENT,
              )
              .post(Dawarich.batchBody(batch.map { it.feature }).toRequestBody(JSON))
              .build()
      val code =
          try {
            client.newCall(request).execute().use { it.code }
          } catch (e: IOException) {
            Timber.w(e, "Dawarich upload of ${batch.size} points failed")
            return Result.retry()
          } catch (e: IllegalArgumentException) {
            Timber.e(e, "Dawarich URL ${preferences.dawarichUrl} is not valid")
            return Result.failure()
          }
      when (Dawarich.outcome(code)) {
        Dawarich.Outcome.SENT -> {
          Timber.d("Dawarich took ${batch.size} points")
          uploader.dao.delete(batch.map { it.id })
        }
        Dawarich.Outcome.REJECTED -> {
          Timber.e("Dawarich rejected ${batch.size} points with HTTP $code, dropping them")
          uploader.dao.delete(batch.map { it.id })
        }
        Dawarich.Outcome.UNAUTHORIZED -> {
          Timber.e("Dawarich refused the API key or URL with HTTP $code, keeping the points")
          return Result.failure()
        }
        Dawarich.Outcome.RETRY -> {
          Timber.w("Dawarich answered HTTP $code, retrying later")
          return Result.retry()
        }
      }
    }
  }

  companion object {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
  }
}
