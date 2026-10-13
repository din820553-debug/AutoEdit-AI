package com.autoedit.ai

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.autoedit.ai.edit.AutoPlanner
import com.autoedit.ai.edit.BeatAnalyzer
import com.autoedit.ai.edit.EditSettings
import com.autoedit.ai.edit.ExportController
import com.autoedit.ai.edit.SourceClip
import com.autoedit.ai.edit.ShotSelector
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Import -> optionally analyze music -> plan short beat cuts -> Media3 export.
 * There is no network, server, login, or broad storage permission.
 */
class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var sourceClips = listOf<SourceClip>()
    private var musicUri: Uri? = null
    private var musicLengthMs = 0L
    private var controller: ExportController? = null
    private var musicPlayer: MediaPlayer? = null
    private var busy = false

    private lateinit var videosStatus: TextView
    private lateinit var musicStatus: TextView
    private lateinit var previewButton: Button
    private lateinit var presetStatus: TextView
    private lateinit var smartShots: CheckBox
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var cancelButton: Button
    private lateinit var duration: Setting
    private lateinit var bpm: Setting
    private lateinit var beatsPerCut: Setting
    private lateinit var beatOffset: Setting
    private lateinit var speed: Setting
    private lateinit var intensity: Setting
    private lateinit var startMusic: Setting
    private lateinit var volume: Setting
    private lateinit var saturation: Setting
    private lateinit var brightness: Setting
    private lateinit var quality: CheckBox
    private lateinit var fps60: CheckBox
    private lateinit var zoom: CheckBox
    private lateinit var audioFromClips: CheckBox
    private lateinit var autoBpm: CheckBox

    private val openVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        videosStatus.text = "Читаю видео..."
        uris.forEach { persistReadAccess(it) }
        io.execute {
            val valid = uris.mapNotNull { uri ->
                val ms = durationOf(uri)
                if (ms >= 500) SourceClip(uri.toString(), ms) else null
            }
            runOnUiThread {
                sourceClips = valid
                videosStatus.text = if (valid.isEmpty()) "Не удалось открыть видео" else
                    "Выбрано видео: ${valid.size} · ${valid.sumOf { it.durationMs } / 1000} сек исходников"
            }
        }
    }

    private val openMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        releasePreview()
        persistReadAccess(uri)
        musicUri = uri
        musicStatus.text = "Читаю музыкальный файл..."
        io.execute {
            val length = durationOf(uri)
            runOnUiThread {
                musicLengthMs = length
                val limit = if (length > 0) (length / 1000 - 1).coerceIn(0,300).toInt() else 300
                startMusic.seekBar.max = limit
                if (startMusic.value > limit) startMusic.seekBar.progress = limit
                val name = documentName(uri)
                musicStatus.text = if (length > 0)
                    "$name · ${length / 1000} сек"
                else "$name · длительность не распознана"
                previewButton.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 12, 18)
        window.navigationBarColor = Color.rgb(11, 12, 18)
        drawUi()
    }

    private fun drawUi() {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Color.rgb(11, 12, 18))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(28),dp(18),dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("AutoEdit AI", 31f, 0xFFEDEAFF.toInt(), true))
        root.addView(text("AUTO SHORTS STUDIO  ·  v0.2", 12f, 0xFFAF93FF.toInt()))
        gap(root, 18)

        val import = card(root,"01  Исходные материалы")
        button(import, "＋  Выбрать видео", true) { if (!busy) openVideos.launch(arrayOf("video/*")) }
        videosStatus = text("Видео пока не добавлены", 14f, 0xFFC9CAD7.toInt())
        import.addView(videosStatus)
        gap(import, 10)
        button(import, "♫  Выбрать музыку (MP3, M4A, WAV)", false) {
            if (!busy) openMusic.launch(arrayOf("audio/*"))
        }
        musicStatus = text("Необязательно · можно экспортировать без музыки", 13f, 0xFFC9CAD7.toInt())
        import.addView(musicStatus)
        previewButton = button(import, "▶  Послушать выбранную музыку", false) { togglePreview() }
        previewButton.isEnabled = false
        button(import, "✕  Убрать музыку", false) {
            if (!busy) {
                releasePreview()
                musicUri = null
                musicLengthMs = 0L
                startMusic.seekBar.max = 300
                startMusic.seekBar.progress = 0
                musicStatus.text = "Музыка не выбрана"
                previewButton.isEnabled = false
            }
        }

        val presets = card(root, "02  Стили монтажа")
        presets.addView(text("Настройки ниже остаются доступными после выбора стиля.", 13f, 0xFFC9CAD7.toInt()))
        gap(presets, 8)
        button(presets, "◈  CINEMATIC — тёмный и плавный", false) { applyPreset("CINEMATIC") }
        button(presets, "⚡  PHONK — быстрые склейки", false) { applyPreset("PHONK") }
        button(presets, "◇  SMOOTH — мягкая динамика", false) { applyPreset("SMOOTH") }
        presetStatus = text("Стиль: CINEMATIC · можно изменить вручную", 13f, 0xFFAF93FF.toInt())
        presets.addView(presetStatus)

        val rhythm = card(root,"03  Монтаж под ритм")
        autoBpm = check(rhythm,"Автоматически определить BPM музыки",true)
        bpm = slider(rhythm,"BPM вручную / запасной вариант",70,180,120) { "$it BPM" }
        beatsPerCut = slider(rhythm,"Частота смены кадров",1,4,2) { "каждые $it уд." }
        beatOffset = slider(rhythm,"Смещение ритма при ручном BPM",0,1000,0) { "$it мс" }
        duration = slider(rhythm,"Длительность Shorts",5,60,15) { "$it сек" }
        smartShots = check(rhythm,"Умный выбор фрагментов (анализ кадров на устройстве)",true)
        speed = slider(rhythm,"Скорость видео",50,175,100) { "${String.format(Locale.US,"%.2f",it/100f)}×" }

        val styling = card(root,"04  Цвет и эффекты")
        intensity = slider(styling,"Сила приближения",0,100,65) { "$it%" }
        zoom = check(styling,"Плавный zoom на каждом кадре",true)
        saturation = slider(styling,"Насыщенность", -40,40,-10) { if(it>0) "+$it" else "$it" }
        brightness = slider(styling,"Светлота", -25,25,-8) { if(it>0) "+$it" else "$it" }

        val sound = card(root,"05  Музыка и экспорт")
        startMusic = slider(sound,"Начало музыкального трека",0,300,0) { "$it сек" }
        volume = slider(sound,"Громкость музыки",0,100,85) { "$it%" }
        audioFromClips = check(sound,"Оставить звук исходников (только без музыки)",false)
        quality = check(sound,"Full HD · 1080×1920 (выкл. = 720×1280)",true)
        fps60 = check(sound,"60 FPS (выкл. = 30 FPS)",false)

        exportButton = button(root,"✦  СОЗДАТЬ SHORTS",true) { exportNow() }
        progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        addWithMargin(root,progress,10)
        cancelButton = button(root,"Отменить экспорт",false) {
            controller?.cancel()
            finishBusy("Экспорт отменён")
        }
        cancelButton.isEnabled = false
        status = text("Готово к монтажу · всё обрабатывается на телефоне",13f,0xFFB5B8C9.toInt())
        addWithMargin(root,status,12)
        addWithMargin(root,text("v0.2: умный выбор кадров основан на чёткости, контрасте и свете — без распознавания объектов. Motion blur и сложные переходы пока не реализованы.",12f,0xFF868B9C.toInt()),16)
        applyPreset(getPreferences(MODE_PRIVATE).getString("preset","CINEMATIC") ?: "CINEMATIC")
    }

    private fun exportNow() {
        if (busy) return
        if (sourceClips.isEmpty()) return tell("Сначала выбери хотя бы одно видео")
        releasePreview()
        val audio = musicUri
        val musicOffsetMs = startMusic.value * 1000L
        if (audio != null && musicLengthMs > 0 && musicOffsetMs >= musicLengthMs - 500) {
            return tell("Начало музыки должно быть раньше её окончания")
        }
        val manualBpm = bpm.value
        val baseConfig = EditSettings(
            durationMs = duration.value * 1000L,
            cutEveryMs = 60_000L / manualBpm * beatsPerCut.value,
            speed = speed.value / 100f,
            intensity = intensity.value,
            musicStartMs = musicOffsetMs,
            musicVolume = volume.value / 100f,
            saturation = saturation.value,
            lightness = brightness.value,
            resolutionHeight = if (quality.isChecked) 1920 else 1280,
            fps = if (fps60.isChecked) 60 else 30,
            zoom = zoom.isChecked,
            keepOriginalAudio = audio == null && audioFromClips.isChecked
        )
        busy = true
        exportButton.isEnabled = false
        cancelButton.isEnabled = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Анализ ритма и подготовка монтажа..."

        if (audio != null && autoBpm.isChecked) {
            io.execute {
                val beat = BeatAnalyzer.analyze(this, audio, musicOffsetMs)
                runOnUiThread {
                    if (!busy) return@runOnUiThread
                    val updated = baseConfig.copy(
                        cutEveryMs = (60_000L / (beat?.bpm ?: manualBpm)) * beatsPerCut.value
                    )
                    val grid = beat?.beatTimesMs?.takeIf { it.isNotEmpty() }
                        ?: BeatAnalyzer.manualGrid(manualBpm,beatOffset.value.toLong(),updated.musicStartMs+updated.durationMs+2_000)
                    if (beat == null) status.text = "BPM не определён · используется ручное значение"
                    else status.text = "Найден ритм ≈ ${beat.bpm} BPM"
                    runExport(updated,grid,audio)
                }
            }
        } else {
            val grid = BeatAnalyzer.manualGrid(
                manualBpm,beatOffset.value.toLong(),baseConfig.musicStartMs+baseConfig.durationMs+2_000
            )
            runExport(baseConfig,grid,audio)
        }
    }

    private fun runExport(config: EditSettings, beats: List<Long>, music: Uri?) {
        if (!busy) return
        if (smartShots.isChecked) {
            status.text = "Ищу выразительные фрагменты видео..."
            val sources = sourceClips.toList()
            io.execute {
                val picks = ShotSelector.select(this, sources)
                runOnUiThread {
                    if (busy && !isFinishing) launchExport(config, beats, music, picks)
                }
            }
        } else {
            launchExport(config, beats, music, emptyMap())
        }
    }

    private fun launchExport(
        config: EditSettings, beats: List<Long>, music: Uri?,
        picks: Map<String, List<com.autoedit.ai.edit.ShotCandidate>>
    ) {
        if (!busy) return
        val cuts = try { AutoPlanner.plan(sourceClips, config, beats, picks) }
                   catch(e: Exception) { return finishBusy("Ошибка планирования: ${e.message}") }
        val exportController = ExportController(this)
        controller = exportController
        status.text = "Монтирую ${cuts.size} фрагментов..."
        exportController.export(
            cuts,config,music,
            onProgress = { value -> progress.progress = value; status.text = "Создаю MP4 · $value%" },
            onSuccess = { uri ->
                finishBusy("Готово! Сохранено в Галерею → Movies/AutoEditAI")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri,"video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }
            },
            onFailure = { message -> finishBusy(message) }
        )
    }

    private fun finishBusy(message: String) {
        busy = false
        exportButton.isEnabled = true
        cancelButton.isEnabled = false
        status.text = message
        if (message.startsWith("Готово")) progress.progress = 100
        else progress.visibility = View.GONE
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }

    private fun persistReadAccess(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this,uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        finally { retriever.release() }
    }

    override fun onStop() {
        releasePreview()
        super.onStop()
    }

    override fun onDestroy() {
        releasePreview()
        if (isFinishing) controller?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }


    private fun applyPreset(preset: String) {
        if (busy) return
        when (preset) {
            "PHONK" -> {
                beatsPerCut.seekBar.progress = 0
                speed.seekBar.progress = 120 - speed.min
                intensity.seekBar.progress = 88 - intensity.min
                saturation.seekBar.progress = -8 - saturation.min
                brightness.seekBar.progress = -14 - brightness.min
            }
            "SMOOTH" -> {
                beatsPerCut.seekBar.progress = 4 - beatsPerCut.min
                speed.seekBar.progress = 90 - speed.min
                intensity.seekBar.progress = 28 - intensity.min
                saturation.seekBar.progress = 2 - saturation.min
                brightness.seekBar.progress = 2 - brightness.min
            }
            else -> {
                beatsPerCut.seekBar.progress = 2 - beatsPerCut.min
                speed.seekBar.progress = 100 - speed.min
                intensity.seekBar.progress = 60 - intensity.min
                saturation.seekBar.progress = -12 - saturation.min
                brightness.seekBar.progress = -8 - brightness.min
            }
        }
        zoom.isChecked = true
        presetStatus.text = "Стиль: $preset · доступна ручная настройка"
        getPreferences(MODE_PRIVATE).edit().putString("preset",preset).apply()
    }

    private fun documentName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: (uri.lastPathSegment ?: "Музыка")
    }

    private fun releasePreview() {
        val current = musicPlayer
        musicPlayer = null
        if (current != null) runCatching { current.release() }
        if (::previewButton.isInitialized) previewButton.text = "▶  Послушать выбранную музыку"
    }

    private fun togglePreview() {
        val audio = musicUri ?: return tell("Сначала выбери музыку")
        if (musicPlayer != null) { releasePreview(); return }
        val player = MediaPlayer()
        musicPlayer = player
        previewButton.text = "Подготовка музыки..."
        try {
            player.setDataSource(this, audio)
            player.setOnPreparedListener {
                if (musicPlayer === player) {
                    player.seekTo(startMusic.value * 1000)
                    player.start()
                    previewButton.text = "■  Остановить музыку"
                }
            }
            player.setOnCompletionListener { releasePreview() }
            player.setOnErrorListener { _, _, _ ->
                releasePreview()
                tell("Не получилось воспроизвести этот файл")
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            releasePreview()
            tell("Ошибка аудио: ${e.message}")
        }
    }

    private class Setting(val view: View, val seekBar: SeekBar, val min: Int) {
        val value get() = seekBar.progress + min
    }
    private fun slider(parent: LinearLayout,title:String,min:Int,max:Int,initial:Int,format:(Int)->String):Setting {
        val label = text("",14f,0xFFE5E3EE.toInt())
        parent.addView(label)
        val bar = SeekBar(this)
        bar.max = max-min
        bar.progress = initial-min
        label.text = "$title: ${format(initial)}"
        bar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek:SeekBar?,progress:Int,fromUser:Boolean) {
                label.text = "$title: ${format(min+progress)}"
            }
            override fun onStartTrackingTouch(seek:SeekBar?) {}
            override fun onStopTrackingTouch(seek:SeekBar?) {}
        })
        addWithMargin(parent,bar,4)
        return Setting(bar,bar,min)
    }
    private fun check(parent:LinearLayout,label:String,checked:Boolean):CheckBox {
        val box = CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(0xFFE3E2EC.toInt())
            textSize = 14f
        }
        parent.addView(box)
        return box
    }
    private fun text(value:String,size:Float,color:Int,strong:Boolean=false):TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (strong) setTypeface(typeface,Typeface.BOLD)
        }
    private fun card(root:LinearLayout,title:String):LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),dp(17),dp(16),dp(16))
            background = GradientDrawable().apply {
                setColor(0xFF191B27.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        val heading = text(title,17f,0xFFF6F3FF.toInt(),true)
        addWithMargin(box,heading,12)
        addWithMargin(root,box,16)
        return box
    }
    private fun button(parent:LinearLayout,label:String,primary:Boolean,action:()->Unit):Button {
        val btn = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if(primary) 0xFF151220.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) 0xFFAF93FF.toInt() else 0xFF343549.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
        addWithMargin(parent,btn,10)
        return btn
    }
    private fun addWithMargin(root:LinearLayout,view:View,bottom:Int) {
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(bottom)
        root.addView(view,params)
    }
    private fun gap(parent:LinearLayout,size:Int) {
        parent.addView(View(this),LinearLayout.LayoutParams(1,dp(size)))
    }
    private fun dp(value:Int)= (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun tell(msg:String) { Toast.makeText(this,msg,Toast.LENGTH_LONG).show() }
}
