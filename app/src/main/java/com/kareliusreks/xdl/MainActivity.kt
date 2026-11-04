package com.kareliusreks.xdl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var findBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    private var webView: WebView? = null
    private var pendingDownload: Pair<VideoVariant, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            input.setText(text)
            startSearch()
        }
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "X Загрузчик"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Вставьте ссылку на пост или нажмите «Поделиться» в приложении X"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(16))
        })

        input = EditText(this).apply {
            hint = "https://x.com/…/status/…"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val pasteBtn = Button(this).apply {
            text = "Вставить"
            setOnClickListener { pasteFromClipboard() }
        }
        findBtn = Button(this).apply {
            text = "Найти видео"
            setOnClickListener { startSearch() }
        }
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(findBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        root.addView(row)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("Буфер обмена пуст")
        } else {
            input.setText(text)
            startSearch()
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        findBtn.isEnabled = !busy
    }

    // ---------- Поиск ----------

    private fun startSearch() {
        val id = XApi.extractId(input.text.toString())
        if (id == null) {
            status.text = "Не нашёл в ссылке номер поста. Нужна ссылка вида x.com/имя/status/123…"
            return
        }
        results.removeAllViews()
        status.text = "Ищу видео…"
        setBusy(true)
        computeToken(id) { token ->
            Thread {
                try {
                    val res = XApi.fetch(id, token)
                    runOnUiThread { showResults(res) }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        status.text = "Не удалось получить видео.\n${e.message}"
                    }
                }
            }.start()
        }
    }

    /**
     * Токен для syndication API считается так же, как на сайте X (JS-формула),
     * поэтому вычисляем его настоящим движком JavaScript через WebView.
     */
    private fun computeToken(id: String, cb: (String?) -> Unit) {
        try {
            val wv = webView ?: WebView(this).also {
                it.settings.javaScriptEnabled = true
                webView = it
            }
            val js = "((Number('$id')/1e15)*Math.PI).toString(36).replace(/(0+|\\.)/g,'')"
            wv.evaluateJavascript(js) { raw ->
                val t = raw?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
                cb(t)
            }
        } catch (e: Exception) {
            cb(null)
        }
    }

    private fun showResults(res: TweetVideos) {
        setBusy(false)
        results.removeAllViews()
        val who = if (res.author.isNotEmpty()) "@${res.author}: " else ""
        val txt = res.text.take(200)
        status.text = "$who$txt\n\nНайдено видео: ${res.videos.size}. Выберите качество:"

        res.videos.forEachIndexed { index, variants ->
            if (res.videos.size > 1) {
                results.addView(TextView(this).apply {
                    text = "Видео ${index + 1}"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                    setPadding(0, dp(12), 0, dp(4))
                })
            }
            variants.forEach { v ->
                results.addView(Button(this).apply {
                    text = "Скачать ${v.label}"
                    isAllCaps = false
                    setOnClickListener {
                        val suffix = if (res.videos.size > 1) "_${index + 1}" else ""
                        val q = if (v.height > 0) "_${v.height}p" else ""
                        askNameAndDownload(v, "x_${res.tweetId}$suffix$q")
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    // ---------- Имя файла ----------

    /** Окно выбора имени: своё название или стандартное */
    private fun askNameAndDownload(v: VideoVariant, defaultName: String) {
        val field = EditText(this).apply {
            hint = defaultName
            setSingleLine(true)
            setText("")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Оставьте поле пустым, чтобы сохранить как:\n$defaultName.mp4"
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, dp(8))
            })
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Название видео")
            .setView(box)
            .setPositiveButton("Скачать") { _, _ ->
                val custom = sanitize(field.text.toString())
                download(v, uniqueName(if (custom.isEmpty()) defaultName else custom))
            }
            .setNeutralButton("По умолчанию") { _, _ ->
                download(v, uniqueName(defaultName))
            }
            .setNegativeButton("Отмена", null)
            .show()
        field.requestFocus()
    }

    /** Убирает символы, запрещённые в именах файлов, и расширение, если его ввели */
    private fun sanitize(name: String): String =
        name.trim()
            .removeSuffix(".mp4").removeSuffix(".MP4")
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .trim('.', ' ')
            .take(120)

    /** Если файл с таким именем уже есть — добавляет (2), (3)… */
    private fun uniqueName(base: String): String {
        @Suppress("DEPRECATION")
        val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "X")
        var name = "$base.mp4"
        var n = 2
        while (java.io.File(dir, name).exists() && n < 1000) {
            name = "$base ($n).mp4"
            n++
        }
        return name
    }

    // ---------- Скачивание ----------

    private fun download(v: VideoVariant, fileName: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = v to fileName
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        try {
            val req = DownloadManager.Request(Uri.parse(v.url))
                .setTitle(fileName)
                .setDescription("X Загрузчик")
                .setMimeType("video/mp4")
                .addRequestHeader("User-Agent", "Mozilla/5.0")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "X/$fileName")
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Скачивание началось → Movies/X/$fileName")
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val p = pendingDownload
            pendingDownload = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && p != null) {
                download(p.first, p.second)
            } else {
                toast("Без доступа к памяти сохранить видео не получится")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_STORAGE = 1
    }
}
