package com.autoedit.ai.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sin

@OptIn(UnstableApi::class)
class ExportController(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var temporary: File? = null
    private var cancelled = false

    fun export(
        cuts: List<Cut>,
        config: EditSettings,
        music: Uri?,
        onProgress: (Int) -> Unit,
        onSuccess: (Uri) -> Unit,
        onFailure: (String) -> Unit
    ) {
        require(Looper.myLooper() == Looper.getMainLooper())
        if (transformer != null) return onFailure("Экспорт уже запущен")
        cancelled = false
        try {
            val width = if (config.resolutionHeight == 1920) 1080 else 720
            val effects = cuts.map { cut ->
                val source = MediaItem.Builder()
                    .setUri(Uri.parse(cut.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(cut.inMs)
                            .setEndPositionMs(cut.outMs)
                            .build()
                    ).build()

                val zoomEffect = MatrixTransformation { timestampUs ->
                    val x = timestampUs.toDouble() / 1_000_000.0
                    val strength = config.intensity / 100.0
                    val scale = (1.0 + 0.01 * strength + (if (cut.effectIndex % 2 == 0) x else 1.8-x).coerceIn(0.0,2.0) * 0.025 * strength).toFloat()
                    Matrix().apply { setScale(scale,scale) }
                }
                val videoEffects = mutableListOf<androidx.media3.common.Effect>()
                if (config.zoom && config.intensity > 0) videoEffects += zoomEffect
                if (config.lightness != 0 || config.saturation != 0)
                    videoEffects += HslAdjustment.Builder()
                        .adjustLightness(config.lightness.toFloat())
                        .adjustSaturation(config.saturation.toFloat()).build()
                videoEffects += Presentation.createForWidthAndHeight(
                    width, config.resolutionHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )

                val builder = EditedMediaItem.Builder(source)
                    .setFrameRate(config.fps)
                    .setRemoveAudio(music != null || !config.keepOriginalAudio)
                    .setEffects(Effects(emptyList(),videoEffects))
                if (kotlin.math.abs(cut.speed - 1f) > 0.001f) {
                    builder.setSpeed(object : SpeedProvider {
                        override fun getSpeed(timeUs: Long): Float = cut.speed
                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                    })
                }
                builder.build()
            }

            val sequences = mutableListOf(
                if (music == null && config.keepOriginalAudio)
                    EditedMediaItemSequence.withAudioAndVideoFrom(effects)
                else EditedMediaItemSequence.withVideoFrom(effects)
            )
            if (music != null) {
                val musicItem = MediaItem.Builder().setUri(music)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(config.musicStartMs).build()
                    ).build()
                val gain = GainProcessor(object : GainProcessor.GainProvider {
                    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
                        config.musicVolume.coerceIn(0f, 1f)
                    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long = C.TIME_UNSET
                })
                val audio = EditedMediaItem.Builder(musicItem)
                    .setEffects(Effects(listOf(gain),emptyList())).build()
                sequences += EditedMediaItemSequence.withAudioFrom(listOf(audio))
                    .buildUpon().setIsLooping(true).build()
            }
            val composition = Composition.Builder(sequences).build()
            val output = File(context.cacheDir, "autoedit_${System.currentTimeMillis()}.mp4")
            temporary = output
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        onProgress(100)
                        writer.execute {
                            val saved = runCatching { save(output) }
                            output.delete()
                            main.post {
                                if (cancelled) return@post
                                saved.onSuccess(onSuccess)
                                    .onFailure { onFailure("Не удалось сохранить MP4: ${it.message}") }
                            }
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        transformer = null
                        output.delete()
                        onFailure("Ошибка кодирования: ${exportException.message ?: "неизвестная"}")
                    }
                }).build()
            transformer!!.start(composition, output.absolutePath)
            progressLoop(onProgress)
        } catch (e: Exception) {
            transformer = null
            temporary?.delete()
            onFailure("Не удалось запустить монтаж: ${e.message}")
        }
    }

    private fun progressLoop(onProgress: (Int) -> Unit) {
        main.postDelayed({
            val active = transformer ?: return@postDelayed
            val holder = ProgressHolder()
            if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(holder.progress.coerceIn(0,99))
            }
            if (transformer != null) progressLoop(onProgress)
        }, 350)
    }

    fun cancel() {
        cancelled = true
        transformer?.cancel()
        transformer = null
        temporary?.delete()
    }

    private fun save(file: File): Uri {
        require(file.length() > 1024) { "Выходной файл пустой" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoEditAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore не создал файл")
        try {
            resolver.openOutputStream(uri)?.use { dest ->
                file.inputStream().buffered().use { input -> input.copyTo(dest) }
            } ?: error("Нет доступа к выходному файлу")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },null,null)
            return uri
        } catch(e: Exception) {
            resolver.delete(uri,null,null)
            throw e
        }
    }
}
