package com.questplay.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/** Paquetes habituales de Moonlight XR (release / debug / variante root). Se usa el primero que esté instalado. */
val MOONLIGHT_PACKAGES = listOf(
    "com.gilleece.moonlightxr",
    "com.gilleece.moonlightxr.debug",
    "com.gilleece.moonlightxr.root"
)

/** Activity de Moonlight que arranca un juego directamente (ShortcutTrampoline). Su namespace es com.limelight. */
const val MOONLIGHT_TRAMPOLINE = "com.limelight.ShortcutTrampoline"

/** Se avisa de que falta Moonlight XR solo una vez por arranque de la app (sobrevive a recrear la Activity al ampliar/reducir la ventana). */
private var mlWarningShown = false
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Contenedor con proporción 16:9 */
class AspectFrame(ctx: android.content.Context) : FrameLayout(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
    }
}

class GameVH(
    root: View,
    val image: ImageView,
    val initial: TextView,
    val title: TextView,
    val del: TextView,
    val gear: TextView,
    val badge: TextView,
    val border: View
) : RecyclerView.ViewHolder(root)

class MainActivity : Activity() {

    private lateinit var store: Store
    private val games = mutableListOf<Game>()
    private var editMode = false
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var adapter: GameAdapter
    private lateinit var statusView: TextView
    private lateinit var sortBtn: TextView
    private lateinit var editBtn: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        games.addAll(store.loadGames())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17171B.toInt())
            setPadding(dp(18), dp(14), dp(18), 0)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Library"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        sortBtn = pill("") { showSortMenu() }
        editBtn = pill("EDIT") { toggleEdit() }
        bar.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))
        bar.addView(sortBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(16) })
        bar.addView(editBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(pill("+  ADD QUEST GAME") { addQuestGame() }, LinearLayout.LayoutParams(WRAP, WRAP))
        bar.addView(pill("+  ADD WINDOWS GAME") { addWindowsGame() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        bar.addView(pill("PC") { showSettings() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { showSettings() }
        }
        root.addView(statusView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        val span = (resources.displayMetrics.widthPixels / dp(250)).coerceIn(2, 6)
        adapter = GameAdapter()
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, span)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, dp(16), 0, dp(24))
        }
        root.addView(rv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        updateMl()
        updateSortLabel()
        refresh()
        updateBanner()
        checkMoonlight()
    }

    override fun onResume() {
        super.onResume()
        if (!::statusView.isInitialized) return
        val before = mlInstalled
        updateMl()
        if (before != mlInstalled) adapter.notifyDataSetChanged()
        updateBanner()
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(12), dp(22), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFF2A2A31.toInt())
            cornerRadius = dp(40).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun circle(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        visibility = View.GONE
    }

    private fun gradientFor(name: String): GradientDrawable {
        val h = (abs(name.hashCode()) % 360).toFloat()
        val c1 = Color.HSVToColor(floatArrayOf(h, 0.55f, 0.45f))
        val c2 = Color.HSVToColor(floatArrayOf((h + 40f) % 360f, 0.6f, 0.22f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
    }

    // ---------------------------------------------------------------- Lista
    inner class GameAdapter : RecyclerView.Adapter<GameVH>() {
        var items: List<Game> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameVH {
            val ctx = parent.context
            val frame = AspectFrame(ctx)
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
                clipToOutline = true
            }
            frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            val initial = TextView(ctx).apply {
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            frame.addView(initial, FrameLayout.LayoutParams(MATCH, MATCH))
            // Marco blanco fino que aparece con fade al pasar el cursor por encima
            val border = View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                }
                alpha = 0f
            }
            frame.addView(border, FrameLayout.LayoutParams(MATCH, MATCH))
            val gear = circle("\u2699\uFE0E", 0xFF111111.toInt())
            val del = circle("\u2715", 0xFFE53935.toInt())
            frame.addView(gear, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                marginEnd = dp(8 + 34 + 6)
            })
            frame.addView(del, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            })

            val badge = TextView(ctx).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    setColor(0xFF2E9E4F.toInt())
                    cornerRadius = dp(5).toFloat()
                }
            }
            frame.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(8)
                marginStart = dp(8)
            })

            val label = TextView(ctx).apply {
                textSize = 13f
                setTextColor(0xFFE6E6E6.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(14))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            root.addView(frame, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            val hover = View.OnHoverListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                        border.animate().alpha(1f).setDuration(150).start()
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        // Si el cursor solo pasa a un bot\u00F3n hijo (\u2699/\u2715) sigue dentro de la tarjeta: no se apaga
                        val loc = IntArray(2)
                        root.getLocationOnScreen(loc)
                        val inside = e.rawX >= loc[0] && e.rawX <= loc[0] + root.width &&
                            e.rawY >= loc[1] && e.rawY <= loc[1] + root.height
                        if (!inside) border.animate().alpha(0f).setDuration(200).start()
                    }
                }
                false
            }
            root.setOnHoverListener(hover)
            gear.setOnHoverListener(hover)
            del.setOnHoverListener(hover)
            return GameVH(root, iv, initial, label, del, gear, badge, border)
        }

        override fun onBindViewHolder(h: GameVH, position: Int) {
            val g = items[position]
            h.title.text = g.name
            h.border.animate().cancel()
            h.border.alpha = 0f
            h.badge.text = when (g.type) {
                "QUEST" -> "QUEST"
                else -> "WINDOWS"
            }
            bindImage(g, h)
            val disabled = g.type != "QUEST" && !mlInstalled
            h.itemView.alpha = if (disabled) 0.4f else 1f
            if (disabled) {
                val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                h.image.setColorFilter(gray)
                h.image.background?.colorFilter = gray
            } else {
                h.image.clearColorFilter()
                h.image.background?.colorFilter = null
            }
            val vis = if (editMode) View.VISIBLE else View.GONE
            h.del.visibility = vis
            h.gear.visibility = vis
            h.itemView.setOnClickListener { if (!editMode) launch(g) }
            h.del.setOnClickListener {
                removeCover(g)
                games.removeAll { it.id == g.id }
                refresh()
            }
            h.gear.setOnClickListener { showEditDialog(g) }
        }

        override fun getItemCount() = items.size
    }

    private fun bindImage(g: Game, h: GameVH) {
        h.image.background = gradientFor(g.name)
        h.image.setImageDrawable(null)
        h.image.setPadding(0, 0, 0, 0)
        h.initial.visibility = View.GONE
        val cover = g.coverFile
        if (g.type != "QUEST" && cover != null) {
            val bmp = loadCover(cover)
            if (bmp != null) {
                h.image.scaleType = ImageView.ScaleType.CENTER_CROP
                h.image.setImageBitmap(bmp)
                return
            }
        }
        val pkg = g.packageName
        var done = false
        if (g.type == "QUEST" && pkg != null) {
            try {
                val banner = packageManager.getApplicationBanner(pkg)
                if (banner != null) {
                    h.image.scaleType = ImageView.ScaleType.CENTER_CROP
                    h.image.setImageDrawable(banner)
                } else {
                    h.image.scaleType = ImageView.ScaleType.FIT_CENTER
                    h.image.setPadding(dp(28), dp(28), dp(28), dp(28))
                    h.image.setImageDrawable(packageManager.getApplicationIcon(pkg))
                }
                done = true
            } catch (e: Exception) {
            }
        }
        if (!done) {
            h.initial.text = g.name.take(1).uppercase()
            h.initial.visibility = View.VISIBLE
        }
    }

    private fun sorted(): List<Game> {
        val order = mapOf("QUEST" to 0, "WINDOWS" to 1)
        return when (store.sort) {
            "ZA" -> games.sortedByDescending { it.name.lowercase() }
            "SYSTEM" -> games.sortedWith(compareBy({ order[it.type] ?: 3 }, { it.name.lowercase() }))
            else -> games.sortedBy { it.name.lowercase() }
        }
    }

    private fun refresh() {
        adapter.items = sorted()
        adapter.notifyDataSetChanged()
        store.saveGames(games)
    }

    private fun toggleEdit() {
        editMode = !editMode
        editBtn.text = if (editMode) "DONE" else "EDIT"
        adapter.notifyDataSetChanged()
    }

    private fun updateSortLabel() {
        val s = when (store.sort) {
            "ZA" -> "Z / A"
            "SYSTEM" -> "System"
            else -> "A / Z"
        }
        sortBtn.text = "Sort by: $s  \u25BE"
    }

    private fun showSortMenu() {
        PopupMenu(this, sortBtn).apply {
            menu.add(0, 0, 0, "A / Z")
            menu.add(0, 1, 1, "Z / A")
            menu.add(0, 2, 2, "System (Quest, Windows)")
            setOnMenuItemClickListener {
                store.sort = listOf("AZ", "ZA", "SYSTEM")[it.itemId]
                updateSortLabel()
                refresh()
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- Moonlight XR
    /** false -> los juegos de Windows se muestran en gris porque Moonlight XR no está instalado. */
    private var mlInstalled = true

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Paquete de Moonlight XR: el configurado a mano o el primero de los habituales que esté instalado. */
    private fun mlPackage(): String? {
        val configured = store.moonlightPackage
        if (!configured.isNullOrBlank()) return configured.takeIf { isInstalled(it) }
        return MOONLIGHT_PACKAGES.firstOrNull { isInstalled(it) }
    }

    private fun updateMl() {
        mlInstalled = mlPackage() != null
    }

    private fun pcConfigured() = !store.pcName.isNullOrBlank() || !store.pcUuid.isNullOrBlank()

    private fun updateBanner() {
        when {
            !mlInstalled -> {
                statusView.visibility = View.VISIBLE
                statusView.setBackgroundColor(0xFF7A1F1F.toInt())
                statusView.text = "\u26A0 Moonlight XR no está instalado en las gafas. Los juegos de Windows aparecen en gris. Toca aquí para ver los ajustes."
            }
            !pcConfigured() -> {
                statusView.visibility = View.VISIBLE
                statusView.setBackgroundColor(0xFF24324A.toInt())
                statusView.text = "Falta indicar el PC de Sunshine (el nombre que aparece en Moonlight XR). Toca aquí para configurarlo."
            }
            else -> statusView.visibility = View.GONE
        }
    }

    private fun checkMoonlight() {
        updateMl()
        if (!mlInstalled && !mlWarningShown) {
            mlWarningShown = true
            AlertDialog.Builder(this)
                .setTitle("Moonlight XR no detectado")
                .setMessage("No se encontró Moonlight XR en tus gafas. Lo necesitas para jugar a los juegos de Windows desde QuestPlay. Mientras tanto aparecerán en gris y no se podrán lanzar.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Ajustes del PC: nombre (o UUID) tal como lo conoce Moonlight XR, y paquete de Moonlight si no es el habitual. */
    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        val nameE = EditText(this).apply {
            setText(store.pcName ?: "")
            hint = "Ej: MI-PC"
            setSingleLine()
        }
        val uuidE = EditText(this).apply {
            setText(store.pcUuid ?: "")
            hint = "Opcional (Moonlight XR > PC > Ver detalles)"
            setSingleLine()
        }
        val pkgE = EditText(this).apply {
            setText(store.moonlightPackage ?: "")
            hint = MOONLIGHT_PACKAGES[0]
            setSingleLine()
        }
        box.addView(label("Nombre del PC, exactamente como sale en Moonlight XR"))
        box.addView(nameE)
        box.addView(label("UUID del PC (opcional; si lo pones se usa en lugar del nombre)"))
        box.addView(uuidE)
        box.addView(label("Paquete de Moonlight XR (vacío = detectar automáticamente)"))
        box.addView(pkgE)
        AlertDialog.Builder(this)
            .setTitle("PC con Sunshine")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val uuid = uuidE.text.toString().trim()
                if (uuid.isNotEmpty()) {
                    try {
                        java.util.UUID.fromString(uuid)
                    } catch (e: Exception) {
                        toast("El UUID no es válido; no se ha guardado")
                        return@setPositiveButton
                    }
                }
                store.pcName = nameE.text.toString().trim()
                store.pcUuid = uuid
                store.moonlightPackage = pkgE.text.toString().trim()
                updateMl()
                updateBanner()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- Lanzar
    private fun findLaunchIntent(pkg: String): Intent? {
        val pm = packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        val main = Intent(Intent.ACTION_MAIN).setPackage(pkg)
        val vr = Intent(Intent.ACTION_MAIN).setPackage(pkg).addCategory("com.oculus.intent.category.VR")
        val ri = pm.queryIntentActivities(vr, 0).firstOrNull() ?: pm.queryIntentActivities(main, 0).firstOrNull()
        ri ?: return null
        return Intent(Intent.ACTION_MAIN).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
    }

    private fun launch(g: Game) {
        if (g.type == "QUEST") {
            val i = g.packageName?.let { findLaunchIntent(it) }
            if (i == null) {
                toast("No se pudo abrir ${g.name}. ¿Sigue instalado?")
                return
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } else {
            launchWindows(g)
        }
    }

    /**
     * Llamada directa a Moonlight XR: ShortcutTrampoline recibe el PC (Name/UUID) y la app de Sunshine (AppName),
     * conecta con el PC (con Wake-on-LAN si hace falta) e inicia el stream. Si "Stream in VR" está activado en
     * Moonlight XR, el stream se abre en modo inmersivo.
     */
    private fun launchWindows(g: Game) {
        updateMl()
        val pkg = mlPackage()
        if (pkg == null) {
            adapter.notifyDataSetChanged()
            updateBanner()
            toast("Moonlight XR no está instalado: no se pueden lanzar juegos de Windows")
            return
        }
        if (!pcConfigured()) {
            toast("Primero indica el PC de Sunshine")
            showSettings()
            return
        }
        val appName = g.sunshineApp?.takeIf { it.isNotBlank() } ?: g.name
        val i = Intent().apply {
            component = ComponentName(pkg, MOONLIGHT_TRAMPOLINE)
            store.pcUuid?.takeIf { it.isNotBlank() }?.let { putExtra("UUID", it) }
            store.pcName?.takeIf { it.isNotBlank() }?.let { putExtra("Name", it) }
            putExtra("AppName", appName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("No se pudo abrir Moonlight XR: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- Car\u00E1tulas
    private val coverCache = LruCache<String, Bitmap>(20)

    private fun loadCover(path: String): Bitmap? {
        val f = File(path)
        if (!f.exists()) return null
        val key = path + f.lastModified()
        coverCache.get(key)?.let { return it }
        val b = BitmapFactory.decodeFile(path) ?: return null
        coverCache.put(key, b)
        return b
    }

    private fun removeCover(g: Game) {
        g.coverFile?.let { File(it).delete() }
        g.coverFile = null
    }

    /** Guarda la imagen reducida (m\u00E1x. 800 px de ancho) en el almacenamiento de la app. */
    private fun saveCover(g: Game, bytes: ByteArray): Boolean {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / sample > 1000) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return false
            val dir = File(filesDir, "covers")
            dir.mkdirs()
            val f = File(dir, "${g.id}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            g.coverFile = f.absolutePath
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- Selector de carátula
    private val reqCover = 4711
    private var coverTarget: Game? = null
    private var coverDone: (() -> Unit)? = null

    /** Abre el selector de archivos de las gafas para elegir una imagen (jpg/png) como carátula. */
    private fun pickImage(g: Game, then: () -> Unit) {
        coverTarget = g
        coverDone = then
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(i, reqCover)
        } catch (e: Exception) {
            coverTarget = null
            coverDone = null
            toast("Estas gafas no tienen selector de archivos para elegir la carátula")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCover) return
        val g = coverTarget
        val done = coverDone
        coverTarget = null
        coverDone = null
        val uri = data?.data
        if (g == null || resultCode != RESULT_OK || uri == null) return
        io.execute {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            val ok = bytes != null && saveCover(g, bytes)
            ui.post {
                if (!ok) toast("No se pudo cargar la imagen")
                done?.invoke()
            }
        }
    }

    // ---------------------------------------------------------------- A\u00F1adir
    private fun addQuestGame(includeSystem: Boolean = false) {
        val pm = packageManager
        val existing = games.mapNotNull { it.packageName }.toSet()
        val apps = pm.getInstalledApplications(0)
            .filter {
                (includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0) &&
                    it.packageName != packageName &&
                    it.packageName !in existing &&
                    findLaunchIntent(it.packageName) != null
            }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            val d = AlertDialog.Builder(this)
                .setTitle("A\u00F1adir juegos de Quest")
                .setMessage("No hay aplicaciones instaladas para a\u00F1adir.")
                .setPositiveButton("OK", null)
            if (!includeSystem) d.setNeutralButton("Mostrar apps del sistema") { _, _ -> addQuestGame(true) }
            d.show()
            return
        }
        val checked = BooleanArray(apps.size)
        val b = AlertDialog.Builder(this)
            .setTitle("A\u00F1adir juegos de Quest")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("A\u00F1adir") { _, _ ->
                apps.forEachIndexed { i, a ->
                    if (checked[i]) games.add(Game(name = a.second, type = "QUEST", packageName = a.first))
                }
                refresh()
            }
            .setNegativeButton("Cancelar", null)
        if (!includeSystem) b.setNeutralButton("Mostrar todas") { _, _ -> addQuestGame(true) }
        b.show()
    }

    /** Asistente para un juego de Windows nuevo: nombre, app de Sunshine y carátula (opcional). */
    private fun addWindowsGame() {
        gameDialog(Game(name = "", type = "WINDOWS"), true)
    }

    private fun showEditDialog(g: Game) {
        gameDialog(g, false)
    }

    private fun addCoverButtons(box: LinearLayout, g: Game) {
        val pick = Button(this)
        val remove = Button(this)
        fun update() {
            pick.text = if (g.coverFile != null) "Cambiar carátula…" else "Elegir carátula…"
            remove.visibility = if (g.coverFile != null) View.VISIBLE else View.GONE
        }
        pick.setOnClickListener {
            pickImage(g) {
                update()
                refresh()
            }
        }
        remove.text = "Quitar carátula"
        remove.setOnClickListener {
            removeCover(g)
            update()
            refresh()
        }
        update()
        box.addView(pick)
        box.addView(remove)
    }

    /**
     * Diálogo para crear/editar un juego. Quest: solo el nombre a mostrar.
     * Windows: nombre, nombre exacto de la aplicación en Sunshine y carátula.
     */
    private fun gameDialog(g: Game, isNew: Boolean) {
        val isPc = g.type != "QUEST"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val nameE = EditText(this).apply {
            setText(g.name)
            hint = "Nombre a mostrar"
            setSingleLine()
        }
        box.addView(nameE)
        var appE: EditText? = null
        if (isPc) {
            appE = EditText(this).apply {
                setText(g.sunshineApp ?: "")
                hint = "Nombre exacto de la app en Sunshine (vacío = el mismo)"
                setSingleLine()
            }
            box.addView(appE)
            addCoverButtons(box, g)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isNew) "Añadir juego de Windows" else "Configurar juego")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val n = nameE.text.toString().trim()
                if (isNew && n.isEmpty()) {
                    toast("Pon un nombre al juego")
                    removeCover(g)
                    return@setPositiveButton
                }
                if (n.isNotEmpty()) g.name = n
                if (isPc) g.sunshineApp = appE?.text?.toString()?.trim().orEmpty().ifEmpty { g.name }
                if (isNew) games.add(g)
                refresh()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                if (isNew) removeCover(g)
            }
            .show()
    }
}
