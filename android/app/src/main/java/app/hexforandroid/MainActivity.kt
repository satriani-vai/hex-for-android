package app.hexforandroid

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Hex for Android's one window. The page keeps what people change: the
 * status, setup while anything is missing, the bubble, dictation and recent
 * dictations. Clean-up steps, practice, full history and the rarely changed
 * settings live in sheets or under Advanced, as desktop Hex hides them.
 */
class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var settings: Settings
    private lateinit var root: LinearLayout

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusLine: TextView
    private lateinit var statusAction: LinearLayout
    private lateinit var setupSection: View
    private lateinit var setupCard: Ui.Card
    private lateinit var stage: DotScope
    private lateinit var vocabularyValue: TextView
    private lateinit var cleanupRow: Ui.Row
    private lateinit var recentCard: Ui.Card
    private lateinit var modelRow: Ui.Row
    private lateinit var languageRow: Ui.Row
    private lateinit var soundsRow: Ui.Row
    private lateinit var syncRow: Ui.Row
    private lateinit var advanced: View
    private lateinit var advancedChevron: ImageView
    private lateinit var footer: TextView
    private var downloading: String? = null
    private var downloadingModel: ModelStore.Model? = null
    private var downloadProgress = 0
    /** The user went to the accessibility settings once; if Hex is still off there, Android may have greyed it out. */
    private var askedAccessibility = false
    private var stageWidth: ValueAnimator? = null

    private var shown: List<Any?>? = null
    private val listener: (Dictation.State) -> Unit = { state ->
        stage.update(state)
        if (state.shape != shown) {
            shown = state.shape
            refresh()
            followPill(state.phase)
        }
    }
    private val vocabularyListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        settings = Settings(this)
        Dictation.init(this)
        askedAccessibility = savedInstanceState?.getBoolean("askedAccessibility") ?: false
        window.decorView.setBackgroundColor(ui.t.window)
        root = ui.vertical { setPadding(ui.px(16), ui.px(16), ui.px(16), ui.px(36)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ui.t.window)
            addView(root)
            // Android 15+ draws edge to edge: keep clear of the system bars and keyboard.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        build()
        if (savedInstanceState?.getBoolean("advanced") == true) toggleAdvanced()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("askedAccessibility", askedAccessibility)
        outState.putBoolean("advanced", advanced.visibility == View.VISIBLE)
    }

    override fun onResume() {
        super.onResume()
        shown = null
        Dictation.listen(listener)
        Vocabulary.listen(vocabularyListener)
        Vocabulary.sync(this, minGapMs = 30_000)
        refresh()
    }

    override fun onPause() {
        Dictation.unlisten(listener)
        Vocabulary.unlisten(vocabularyListener)
        super.onPause()
    }

    // --- Page -----------------------------------------------------------------

    private fun build() {
        val t = ui.t
        root.addView(ui.horizontal {
            setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(16))
            addView(ui.icon(R.drawable.hex_mark, 24))
            addView(View(context), LinearLayout.LayoutParams(ui.px(12), 1))
            addView(ui.text(getString(R.string.app_name), 22f, ui.semibold))
        })

        statusDot = ui.statusDot(t.faint)
        statusTitle = ui.title("")
        statusLine = ui.description("")
        statusAction = ui.horizontal()
        root.addView(ui.card().apply {
            row(ui.horizontal {
                setPadding(ui.px(16), ui.px(14), ui.px(14), ui.px(14))
                addView(ui.vertical {
                    addView(ui.horizontal {
                        addView(statusDot)
                        addView(View(context), LinearLayout.LayoutParams(ui.px(10), 1))
                        addView(statusTitle)
                    })
                    addView(statusLine.apply { setPadding(ui.px(18), ui.px(4), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(context), LinearLayout.LayoutParams(ui.px(12), 1))
                addView(statusAction)
            })
        })

        setupCard = ui.card()
        setupSection = ui.vertical {
            addView(ui.section("Set up"))
            addView(setupCard)
        }
        root.addView(setupSection)

        // Bubble: what it looks like and when it shows.
        root.addView(ui.section("Bubble"))
        stage = DotScope(this).apply {
            shape = BubbleShape.of(settings.bubbleShape)
            size = BubbleSize.of(settings.bubbleSize)
            contentDescription = "Bubble preview"
            showsReadyWhenOff = true
        }
        root.addView(ui.card().apply {
            row(FrameLayout(context).apply {
                minimumHeight = ui.px(108)
                addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                addView(ui.button("Preview") { Dictation.preview() }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM,
                ).apply { setMargins(0, 0, ui.px(12), ui.px(12)) })
            })
            row(ui.row("Shape", trailing = ui.iconSegmented(
                BubbleShape.entries.map { Triple(it.name, it.icon, it.label) }, BubbleShape.of(settings.bubbleShape).name,
            ) {
                settings.bubbleShape = it
                restyle()
            }))
            row(ui.row("Size", trailing = ui.segmented(
                BubbleSize.entries.map { it.name to it.label }, BubbleSize.of(settings.bubbleSize).name,
            ) {
                settings.bubbleSize = it
                restyle()
            }))
            row(ui.row("Show", below = ui.segmented(
                listOf("keyboard" to "While typing", "always" to "Always"), settings.bubbleVisibility,
            ) {
                settings.bubbleVisibility = it
                HexAccessibilityService.instance?.refreshBubble()
            }))
        })

        // Dictation: what Hex writes.
        root.addView(ui.section("Dictation"))
        val vocabulary = ui.sheetValue("")
        vocabularyValue = vocabulary.getChildAt(0) as TextView
        cleanupRow = ui.row("Clean-up", "", R.drawable.hex_icon_sparkles, trailing = chevron()) { cleanupSheet() }
        modelRow = ui.row("Speech model", "", R.drawable.hex_icon_wave, trailing = chevron()) { modelSheet() }
        languageRow = ui.row("Language", "", R.drawable.hex_icon_text_tool, trailing = chevron()) { languageSheet() }
        root.addView(ui.card().apply {
            row(ui.row("Vocabulary", icon = R.drawable.hex_icon_book, trailing = vocabulary) { vocabularySheet() })
            row(cleanupRow)
            row(modelRow)
            row(languageRow)
            row(ui.row("Practice", "Try the bubble without leaving Hex", R.drawable.hex_icon_mic, trailing = chevron()) { practiceSheet() })
        })

        root.addView(ui.horizontal {
            setPadding(ui.px(4), ui.px(24), ui.px(4), ui.px(8))
            addView(ui.text("Recent", 15f, ui.medium, t.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("Show all", 13f, ui.medium, t.accent).apply { setOnClickListener { historySheet() } })
        })
        recentCard = ui.card()
        root.addView(recentCard)

        // Advanced, collapsed: the settings people rarely change.
        advancedChevron = ui.icon(R.drawable.hex_chevron, 14, t.muted)
        root.addView(ui.horizontal {
            setPadding(ui.px(4), ui.px(24), ui.px(4), ui.px(8))
            addView(ui.text("Advanced", 15f, ui.medium, t.muted))
            addView(View(context), LinearLayout.LayoutParams(ui.px(6), 1))
            addView(advancedChevron)
            setOnClickListener { toggleAdvanced() }
        })
        soundsRow = ui.row("Sounds", "", R.drawable.hex_icon_speaker, trailing = chevron()) { soundsSheet() }
        syncRow = ui.row("Desktop sync", "", R.drawable.hex_icon_refresh, trailing = chevron()) { syncSheet() }
        advanced = ui.card().apply {
            visibility = View.GONE
            row(soundsRow)
            row(ui.row("Insert text", "Paste uses the clipboard", R.drawable.hex_icon_paste, below = ui.segmented(
                listOf("auto" to "Type", "paste" to "Paste"), settings.insertMode,
            ) { settings.insertMode = it }))
            row(ui.row("Lower other audio", icon = R.drawable.hex_icon_volume, trailing = ui.switch(settings.duckAudio) {
                settings.duckAudio = it
            }))
            row(syncRow)
            row(ui.row("CPU threads", icon = R.drawable.hex_icon_zap, below = ui.segmented(
                listOf("0" to "Auto", "2" to "2", "4" to "4", "6" to "6"), settings.threads.toString(),
            ) {
                settings.threads = it.toInt()
                if (DictationService.running) toast("Applies when Hex next turns on")
            }))
        }
        root.addView(advanced)

        footer = ui.text("", 12f, ui.regular, t.faint).apply {
            setPadding(ui.px(4), ui.px(22), ui.px(4), ui.px(8))
            setLineSpacing(0f, 1.4f)
            setOnClickListener { aboutSheet() }
        }
        root.addView(footer)
    }

    private fun chevron() = ui.icon(R.drawable.hex_chevron, 16, ui.t.muted)

    private fun toggleAdvanced() {
        val open = advanced.visibility != View.VISIBLE
        advanced.visibility = if (open) View.VISIBLE else View.GONE
        advancedChevron.animate().rotation(if (open) 90f else 0f).setDuration(150).start()
    }

    private fun restyle() {
        stage.shape = BubbleShape.of(settings.bubbleShape)
        stage.size = BubbleSize.of(settings.bubbleSize)
        HexAccessibilityService.instance?.restyleBubble()
    }

    /** The preview pill widens and narrows with the phase, as the bubble does. */
    private fun followPill(phase: Dictation.Phase) {
        if (stage.shape != BubbleShape.Pill) return
        val target = when (phase) {
            Dictation.Phase.Recording -> DotScope.RECORDING_WIDTH
            Dictation.Phase.Transcribing -> DotScope.PROCESSING_WIDTH
            else -> DotScope.REST_WIDTH
        }
        if (target == stage.pillWidth) return
        stageWidth?.cancel()
        stageWidth = ValueAnimator.ofFloat(stage.pillWidth, target).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { stage.pillWidth = it.animatedValue as Float }
            start()
        }
    }

    // --- State ----------------------------------------------------------------

    private fun refresh() {
        if (!::footer.isInitialized) return
        val t = ui.t
        val state = Dictation.state
        val missing = setupSteps().filterNot { it.done() }

        val (dot, title, line) = when {
            state.phase == Dictation.Phase.Loading -> Triple(t.accent, "Starting", "Loading the speech model…")
            state.phase == Dictation.Phase.Recording && !Dictation.previewing -> Triple(t.danger, "Listening", "Tap the bubble to stop")
            state.phase == Dictation.Phase.Transcribing && !Dictation.previewing -> Triple(t.accent, "Transcribing", "")
            DictationService.running -> Triple(t.success, "On", "Tap the bubble, or hold it to talk")
            missing.isNotEmpty() -> Triple(t.faint, "Off", "Finish setting up below")
            else -> Triple(t.faint, "Off", "Turn on to show the bubble")
        }
        statusDot.background = ui.rounded(dot, 4f)
        statusTitle.text = title
        val message = state.message ?: line
        statusLine.text = message
        statusLine.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        statusAction.removeAllViews()
        statusAction.addView(
            if (DictationService.running) ui.button("Turn off") { DictationService.stop(this); later { refresh() } }
            else ui.button("Turn on", primary = missing.isEmpty()) { turnOn() },
        )

        setupCard.removeAllViews()
        missing.forEach { step -> setupCard.row(step.row()) }
        setupSection.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        vocabularyValue.text = when (val terms = Vocabulary.entries(this).size) {
            0 -> "None"
            1 -> "1 term"
            else -> "$terms terms"
        }
        cleanupRow.describe(cleanupSummary())
        languageRow.describe(when (settings.language) {
            ModelStore.AUTO -> "Auto · ${settings.model().languages.size} languages"
            else -> ModelStore.languageLabel(settings.language)
        })
        modelRow.describe(when {
            downloading != null -> "$downloading $downloadProgress%"
            ModelStore.isPresent(this, settings.model()) -> settings.model().name
            else -> "${settings.model().name} · not downloaded"
        })

        soundsRow.describe(if (settings.sounds) Sounds.Set.of(settings.soundSet).label else "Off")
        syncRow.describe(if (Vocabulary.paired(this)) Vocabulary.status(this) else "Off")

        recentCard.removeAllViews()
        val items = History.items(this).take(3)
        if (items.isEmpty()) recentCard.row(ui.row("Nothing yet", "Your dictations show here"))
        items.forEach { recentCard.row(historyRow(it)) }

        footer.text = "${getString(R.string.app_name)} ${versionName()} · About"
    }

    private fun cleanupSummary(): String {
        val on = listOfNotNull(
            "Formatting".takeIf { settings.spokenFormatting },
            "Symbols".takeIf { settings.spokenSymbols },
            "Fillers".takeIf { settings.fillerWords },
            settings.replacements().size.takeIf { it > 0 }?.let { if (it == 1) "1 replacement" else "$it replacements" },
        )
        return if (on.isEmpty()) "Off" else on.joinToString(" · ")
    }

    private fun historyRow(item: History.Item) = ui.horizontal {
        setPadding(ui.px(16), ui.px(12), ui.px(14), ui.px(12))
        gravity = Gravity.TOP
        addView(ui.text(item.text, 14f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        History.ago(item.at).takeIf { it.isNotEmpty() }?.let {
            addView(View(context), LinearLayout.LayoutParams(ui.px(12), 1))
            addView(ui.text(it, 12f, ui.regular, ui.t.faint))
        }
        background = ui.pressable(ui.t.surface, 0f)
        setOnClickListener {
            Inserter.copy(this@MainActivity, item.text)
            toast("Copied")
        }
    }

    private fun versionName() = packageManager.getPackageInfo(packageName, 0).versionName

    private fun turnOn() {
        // Check now, not at the last refresh: setup may have finished meanwhile.
        if (setupSteps().any { !it.done() }) {
            refresh()
            toast("Finish setting up first")
            return
        }
        if (!DictationService.start(this)) toast("Android didn't let Hex start. Try again")
        later { refresh() }
    }

    // --- Setup ----------------------------------------------------------------

    private inner class SetupStep(
        val title: String,
        val description: String,
        val icon: Int,
        val action: String,
        val done: () -> Boolean,
        val run: () -> Unit,
    ) {
        fun row() = ui.row(title, description, icon, below = ui.button(action) { run() })
    }

    private fun setupSteps(): List<SetupStep> {
        val accessibility = if (askedAccessibility && Build.VERSION.SDK_INT >= 33) {
            // Android greys out the accessibility switch of an app installed outside a store.
            SetupStep("Accessibility", "Greyed out? In App info, open ⋮ › Allow restricted settings", R.drawable.hex_icon_hand, "App info",
                { accessibilityOn() },
                {
                    askedAccessibility = false
                    startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
        } else {
            SetupStep("Accessibility", "Shows the bubble and types for you", R.drawable.hex_icon_hand, "Open",
                { accessibilityOn() },
                {
                    askedAccessibility = true
                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                })
        }
        return listOfNotNull(
            SetupStep("Microphone", "Records only while you dictate", R.drawable.hex_icon_mic, "Allow",
                { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }),
            if (Build.VERSION.SDK_INT >= 33) SetupStep("Notifications", "Shows when Hex is on", R.drawable.hex_icon_bell, "Allow",
                { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }) else null,
            accessibility,
            SetupStep("Battery", "Keeps Hex ready in the background", R.drawable.hex_icon_zap, "Allow",
                { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
                { startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }),
            SetupStep("Speech model",
                if (downloading != null) "$downloading $downloadProgress%"
                else "${settings.model().name} · ${settings.model().sizeLabel}",
                R.drawable.hex_icon_wave, if (ModelStore.isPresent(this, settings.model())) "Verify" else "Download",
                { ModelStore.isVerified(this, settings.model()) },
                { fetchModel(settings.model()) }),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val name = ComponentName(this, HexAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == name }
    }

    /** Downloads (or verifies) one model and selects it once it is in place. */
    private fun fetchModel(model: ModelStore.Model) {
        if (downloading != null) return
        val verifyOnly = ModelStore.isPresent(this, model)
        downloading = if (verifyOnly) "Verifying" else "Downloading"
        downloadingModel = model
        downloadProgress = 0
        refresh()
        thread(name = "hex-model") {
            val result = runCatching {
                if (verifyOnly) {
                    ModelStore.verify(this, model) { f -> progress((f * 100).toInt()) }
                } else {
                    ModelStore.download(this, model) { have, total -> progress((have * 100 / total).toInt()) }
                }
            }
            runOnUiThread {
                val fetched = downloadingModel
                downloading = null
                downloadingModel = null
                result.onFailure { toast("${model.name}: ${it.message}") }
                // A model asked for becomes the one Hex runs with; the engine
                // reloads while Hex is on, else the next arm loads it.
                result.onSuccess { ok ->
                    if (ok) {
                        if (fetched != null) chooseModel(fetched)
                    } else {
                        toast("The model's checksum is wrong. Download it again")
                    }
                }
                refresh()
            }
        }
    }

    /** Applies a picked model and its language, reloading the engine if Hex is on. */
    private fun chooseModel(model: ModelStore.Model) {
        settings.modelId = model.id
        settings.language = ModelStore.languageFor(model, settings.language)
        if (DictationService.running) {
            Dictation.disarm()
            Dictation.arm(this)
        }
        refresh()
    }

    private fun progress(percent: Int) {
        if (percent == downloadProgress) return
        downloadProgress = percent
        runOnUiThread { refresh() }
    }

    // --- Sheets ---------------------------------------------------------------

    private fun cleanupSheet() {
        val sheet = Sheet(this, ui, "Clean-up", listOf("Runs on every dictation, top to bottom"))
        val tryInput = ui.field("Um, open code shipped the hex app today hashtag launch")
        val tryResult = ui.text("", 15f, ui.medium)
        val update = { preview(tryInput, tryResult) }
        val replaceRow = ui.row("Replace words", replacementSummary(), step = 4, trailing = chevron())
        replaceRow.setOnClickListener {
            replacementsSheet {
                replaceRow.describe(replacementSummary())
                update()
            }
        }
        sheet.body.addView(ui.card().apply {
            row(ui.row("Spoken formatting", "New line, open quote, close quote", step = 1, trailing = ui.switch(settings.spokenFormatting) {
                settings.spokenFormatting = it
                update()
            }))
            row(ui.row("Spoken symbols", "Hashtag, underscore, at sign", step = 2, trailing = ui.switch(settings.spokenSymbols) {
                settings.spokenSymbols = it
                update()
            }))
            row(ui.row("Remove filler words", "Um, uh, er", step = 3, trailing = ui.switch(settings.fillerWords) {
                settings.fillerWords = it
                update()
            }))
            row(replaceRow)
            row(ui.vertical {
                setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(14))
                addView(ui.horizontal {
                    addView(ui.text("Try", 14f, ui.medium, ui.t.muted), LinearLayout.LayoutParams(ui.px(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(tryInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(ui.horizontal {
                    setPadding(0, ui.px(10), 0, 0)
                    addView(ui.text("→", 14f, ui.medium, ui.t.faint), LinearLayout.LayoutParams(ui.px(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(tryResult, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            })
        })
        tryInput.addTextChangedListener(watcher(update))
        update()
        sheet.setOnDismissListener { refresh() }
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    private fun replacementSummary(): String {
        val rules = settings.replacements()
        return if (rules.isEmpty()) "None yet" else rules.take(3).joinToString(" · ") { (a, b) -> "$a → ${b.ifEmpty { "(nothing)" }}" }
    }

    private fun practiceSheet() {
        val sheet = Sheet(this, ui, "Practice", listOf("Tap the field, then use the bubble, or Dictate"))
        val field = ui.field("Dictated text appears here").apply {
            minLines = 3
            gravity = Gravity.TOP
        }
        lateinit var button: TextView
        button = ui.button("Dictate") {
            when {
                Dictation.recording -> Dictation.finish { text ->
                    val at = field.selectionEnd.coerceAtLeast(0)
                    val current = field.text.toString()
                    field.text.insert(at, Inserter.spaced(current.substring(0, at), current.substring(at), text))
                }
                !Dictation.armed -> toast("Turn Hex on first")
                else -> Dictation.start()
            }
            button.text = if (Dictation.recording) "Stop" else "Dictate"
        }
        sheet.body.addView(field)
        sheet.actions(listOf(button), listOf(ui.button("Done") {
            if (Dictation.recording) Dictation.cancel()
            sheet.dismiss()
        }))
        sheet.show()
    }

    private fun historySheet() {
        val sheet = Sheet(this, ui, "History", listOf("Tap one to copy it"))
        val card = ui.card()
        val items = History.items(this)
        if (items.isEmpty()) card.row(ui.row("Nothing yet", "Your dictations show here"))
        items.forEach { card.row(historyRow(it)) }
        sheet.body.addView(card)
        sheet.setOnDismissListener { refresh() }
        sheet.actions(
            if (items.isEmpty()) emptyList() else listOf(ui.button("Clear…") { confirmClear(sheet) }),
            listOf(ui.button("Done") { sheet.dismiss() }),
        )
        sheet.show()
    }

    /** Clearing history is two taps: the button turns into a confirmation. */
    private fun confirmClear(sheet: Sheet) {
        val confirm = Sheet(this, ui, "Clear history?", listOf("Hex keeps your last 50 dictations on this phone"))
        confirm.actions(
            listOf(ui.button("Cancel") { confirm.dismiss() }),
            listOf(ui.button("Clear") {
                History.clear(this)
                confirm.dismiss()
                sheet.dismiss()
            }),
        )
        confirm.show()
    }

    private fun soundsSheet() {
        val sheet = Sheet(this, ui, "Sounds", listOf("Desktop Hex's sounds for start, stop and cancel. Tap a set to hear it"))
        val sets = ui.card()
        val checks = mutableMapOf<Sounds.Set, View>()
        fun mark() = checks.forEach { (set, check) -> check.visibility = if (set.id == settings.soundSet) View.VISIBLE else View.INVISIBLE }
        sheet.body.addView(ui.card().apply {
            row(ui.row("Play sounds", trailing = ui.switch(settings.sounds) {
                settings.sounds = it
                if (it) Sounds.play(this@MainActivity, Sounds.Set.of(settings.soundSet), Sounds.Cue.Start)
            }))
        })
        sheet.body.addView(ui.space(12))
        Sounds.Set.entries.forEach { set ->
            val check = ui.icon(R.drawable.hex_check, 18, ui.t.accent)
            checks[set] = check
            sets.row(ui.row(set.label, set.character, trailing = check) {
                settings.soundSet = set.id
                mark()
                Sounds.play(this, set, Sounds.Cue.Start)
                root.postDelayed({ Sounds.play(this, set, Sounds.Cue.Stop) }, 700)
            })
        }
        mark()
        sheet.body.addView(sets)
        sheet.setOnDismissListener { refresh() }
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    private fun syncSheet() {
        if (!Vocabulary.paired(this)) {
            val sheet = Sheet(this, ui, "Desktop sync", listOf(
                "Optional: keeps this vocabulary in step with desktop Hex on a Linux computer, over Tailscale",
                "To set it up, connect this phone to the computer by USB and run hex-vocab-sync pair there. The project's README has the steps",
            ))
            sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
            sheet.show()
            return
        }
        val sheet = Sheet(this, ui, "Desktop sync", listOf(Vocabulary.status(this)))
        sheet.setOnDismissListener { refresh() }
        sheet.actions(
            listOf(ui.button("Unpair") {
                Vocabulary.unpair(this)
                sheet.dismiss()
            }),
            listOf(ui.button("Sync now") {
                Vocabulary.sync(this)
                sheet.dismiss()
            }),
        )
        sheet.show()
    }

    private fun modelSheet() {
        val sheet = Sheet(this, ui, "Speech model", listOf(
            "Pinned to desktop Hex's catalog: the same Hugging Face files and checksums. Each is about 700 MB, so remove the ones you don't use",
            "Parakeet v3 and Nemotron ASR hear which language is spoken, so their Language sheet starts on Auto",
        ))
        val card = ui.card()
        val checks = mutableMapOf<String, View>()
        fun mark() = ModelStore.models.forEach { model ->
            checks[model.id]?.visibility = if (model.id == settings.modelId) View.VISIBLE else View.INVISIBLE
        }
        ModelStore.models.forEach { model ->
            val check = ui.icon(R.drawable.hex_check, 18, ui.t.accent)
            checks[model.id] = check
            val trailing = ui.horizontal {
                addView(check)
                if (model.id != settings.modelId && ModelStore.isPresent(this@MainActivity, model)) {
                    addView(View(context), LinearLayout.LayoutParams(ui.px(8), 1))
                    addView(removeButton { confirmRemove(model) { sheet.dismiss(); modelSheet() } })
                }
            }
            card.row(ui.row(model.name, modelDetail(model), R.drawable.hex_icon_wave, trailing = trailing) {
                when {
                    downloading != null -> toast("A download is already running")
                    ModelStore.isPresent(this@MainActivity, model) -> {
                        chooseModel(model)
                        mark()
                    }
                    else -> {
                        sheet.dismiss()
                        fetchModel(model)
                    }
                }
            })
        }
        mark()
        sheet.body.addView(card)
        val model = settings.model()
        sheet.body.addView(ui.space(12))
        val state = Dictation.state
        sheet.body.addView(ui.card().apply {
            val file = when {
                downloadingModel == model -> "$downloading $downloadProgress%"
                ModelStore.isVerified(this@MainActivity, model) -> "${model.sizeLabel} · verified"
                ModelStore.isPresent(this@MainActivity, model) -> "${model.sizeLabel} · not verified"
                else -> "Not downloaded"
            }
            row(ui.row("File", file, R.drawable.hex_icon_folder))
            row(ui.row("Engine", state.engine ?: "Loads when Hex turns on", R.drawable.hex_icon_terminal))
            state.last?.let {
                row(ui.row("Last dictation", "%.1f s of audio in %.2f s".format(it.audioMs / 1000f, it.inferenceMs / 1000f), R.drawable.hex_icon_timer))
            }
        })
        sheet.setOnDismissListener { refresh() }
        sheet.actions(
            listOf(ui.button(if (ModelStore.isPresent(this, model)) "Verify" else "Download") { sheet.dismiss(); fetchModel(model) }),
            listOf(ui.button("Done") { sheet.dismiss() }),
        )
        sheet.show()
    }

    /** A model's size and languages, with what the phone holds of it. */
    private fun modelDetail(model: ModelStore.Model): String {
        val present = ModelStore.isPresent(this, model)
        return buildString {
            append(model.sizeLabel)
            append(" · ")
            append(model.summary)
            when {
                model == downloadingModel -> append(" · $downloading $downloadProgress%")
                present && !ModelStore.isVerified(this@MainActivity, model) -> append(" · not verified")
                !present -> append(" · not downloaded")
            }
        }
    }

    /** The languages the selected model transcribes. */
    private fun languageSheet() {
        val model = settings.model()
        val sheet = Sheet(this, ui, "Language", listOf(when {
            model.id == ModelStore.Nemotron.id ->
                "Nemotron takes the language you pick. Auto leaves it to hear which of its ${model.languages.size} languages is spoken"
            model.detects ->
                "${model.name} hears which of its ${model.languages.size} languages is spoken, so this says what you expect, not what it needs"
            else ->
                "${model.name} transcribes ${ModelStore.languageName(model.languages.first())} only. Parakeet v3 and Nemotron ASR hear which language is spoken, German and Russian included"
        }))
        val card = ui.card()
        val checks = mutableMapOf<String, View>()
        fun mark() = model.languageChoices().forEach { code ->
            checks[code]?.visibility = if (code == settings.language) View.VISIBLE else View.INVISIBLE
        }
        model.languageChoices().forEach { code ->
            val check = ui.icon(R.drawable.hex_check, 18, ui.t.accent)
            checks[code] = check
            card.row(ui.row(
                ModelStore.languageLabel(code),
                if (code == ModelStore.AUTO) "One of ${model.languages.size} languages" else null,
                trailing = check,
            ) {
                settings.language = code
                mark()
            })
        }
        mark()
        sheet.body.addView(card)
        if (!model.detects) {
            // Only a model that hears the language itself can offer Auto, so
            // say so and lead to the picker instead of leaving a dead end.
            sheet.body.addView(ui.space(12))
            sheet.body.addView(ui.card().apply {
                val others = ModelStore.models.filter { it.detects }
                row(ui.row(
                    "Auto-detect the language",
                    "${others.joinToString(" or ") { it.name }} hear which language is spoken and transcribe German and Russian",
                    R.drawable.hex_icon_wave,
                    trailing = chevron(),
                ) {
                    sheet.dismiss()
                    modelSheet()
                })
            })
        }
        sheet.setOnDismissListener { refresh() }
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    private fun confirmRemove(model: ModelStore.Model, onDone: () -> Unit) {
        val sheet = Sheet(this, ui, "Remove ${model.name}?", listOf(
            "The ${model.sizeLabel} file leaves this phone. You can download it again whenever you want it",
        ))
        sheet.actions(
            listOf(ui.button("Cancel") { sheet.dismiss() }),
            listOf(ui.button("Remove") {
                ModelStore.remove(this, model)
                sheet.dismiss()
                onDone()
            }),
        )
        sheet.show()
    }

    private fun vocabularySheet() {
        val sheet = Sheet(this, ui, "Vocabulary", listOf("Under Sounds like, add what the model writes instead"))
        val sync = ui.description(Vocabulary.status(this))
        val syncLink = ui.text("Sync now", 13f, ui.medium, ui.t.accent).apply { setPadding(ui.px(10), ui.px(6), 0, ui.px(6)) }
        if (Vocabulary.paired(this)) {
            sheet.explainView(ui.horizontal {
                addView(sync, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(syncLink)
            })
        }
        sheet.body.addView(ui.card().apply {
            row(ui.row("Snap to vocabulary", "Bias Parakeet toward these terms", trailing = ui.switch(settings.vocabularyBiasing) {
                settings.vocabularyBiasing = it
            }))
            row(ui.row("Strength", trailing = ui.segmented(
                listOf("light" to "Light", "normal" to "Normal", "strong" to "Strong"), settings.vocabularyBoost,
            ) { settings.vocabularyBoost = it }))
        })
        sheet.body.addView(ui.space(12))
        val rows = ui.vertical()
        fun addRow(term: Term?) {
            val termField = ui.field("Term", term?.term.orEmpty())
            val soundsField = ui.field("Sounds like", term?.soundsLike?.joinToString(", ").orEmpty())
            val row = ui.horizontal { setPadding(0, 0, 0, ui.px(8)) }
            row.addView(termField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            row.addView(View(this), LinearLayout.LayoutParams(ui.px(8), 1))
            row.addView(soundsField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            row.addView(removeButton { rows.removeView(row) })
            row.tag = termField to soundsField
            rows.addView(row)
            if (term == null) termField.requestFocus()
        }
        Vocabulary.entries(this).forEach { addRow(it) }
        if (rows.childCount == 0) addRow(null)
        sheet.body.addView(rows)
        fun collect(): List<Term> = (0 until rows.childCount).mapNotNull { i ->
            val (termField, soundsField) = fields(rows.getChildAt(i))
            val term = termField.text.toString().trim()
            if (term.isEmpty()) null
            else Term(term, soundsField.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
        // Only a sync asked for here rebuilds the rows, so a background sync
        // never discards what is being typed.
        var requested = false
        syncLink.setOnClickListener {
            Vocabulary.save(this, collect(), notify = false)
            requested = true
            Vocabulary.sync(this)
            sync.text = "Syncing…"
        }
        val onSync: () -> Unit = {
            sync.text = Vocabulary.status(this)
            if (requested) {
                requested = false
                rows.removeAllViews()
                Vocabulary.entries(this).forEach { addRow(it) }
                if (rows.childCount == 0) addRow(null)
            }
        }
        Vocabulary.listen(onSync)
        sheet.setOnDismissListener {
            Vocabulary.unlisten(onSync)
            refresh()
        }
        sheet.actions(
            listOf(ui.button("Add term") { addRow(null) }),
            listOf(ui.button("Done") {
                Vocabulary.save(this, collect(), notify = false)
                Vocabulary.sync(this)
                sheet.dismiss()
            }),
        )
        sheet.show()
    }

    private fun replacementsSheet(onDone: () -> Unit) {
        val sheet = Sheet(this, ui, "Replace words", listOf("Matches ignore case. An empty output removes the phrase"))
        val rows = ui.vertical()
        fun addRow(rule: Pair<String, String>?) {
            val heard = ui.field("Heard", rule?.first.orEmpty())
            val output = ui.field("Output", rule?.second.orEmpty())
            val row = ui.horizontal { setPadding(0, 0, 0, ui.px(8)) }
            row.addView(heard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.text("→", 15f, ui.regular, ui.t.faint).apply { setPadding(ui.px(8), 0, ui.px(8), 0) })
            row.addView(output, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(removeButton { rows.removeView(row) })
            row.tag = heard to output
            rows.addView(row)
            if (rule == null) heard.requestFocus()
        }
        settings.replacements().forEach { addRow(it) }
        if (rows.childCount == 0) addRow(null)
        sheet.body.addView(rows)
        sheet.actions(
            listOf(ui.button("Add rule") { addRow(null) }),
            listOf(ui.button("Done") {
                settings.setReplacements((0 until rows.childCount).map { i ->
                    val (heard, output) = fields(rows.getChildAt(i))
                    heard.text.toString() to output.text.toString()
                })
                sheet.dismiss()
                onDone()
            }),
        )
        sheet.show()
    }

    private fun aboutSheet() {
        val sheet = Sheet(this, ui, "About", listOf(
            "An unofficial Android port of Hex. Speech is transcribed on this phone and never leaves it",
        ))
        val card = ui.card()
        card.row(ui.row("Hex", "Anomaly · MIT License", R.drawable.hex_mark))
        card.row(ui.row("transcribe.cpp", "Speech engine · MIT License", R.drawable.hex_icon_terminal))
        card.row(ui.row("Parakeet Unified English", "NVIDIA · CC-BY-4.0", R.drawable.hex_icon_wave))
        card.row(ui.row("Parakeet v3", "NVIDIA · CC-BY-4.0", R.drawable.hex_icon_wave))
        card.row(ui.row("Nemotron 3.5 ASR", "NVIDIA · OpenMDW-1.1", R.drawable.hex_icon_wave))
        card.row(ui.row("Inter, IBM Plex Mono", "SIL Open Font License 1.1", R.drawable.hex_icon_text_tool))
        sheet.body.addView(card)
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    // --- Helpers --------------------------------------------------------------

    /** The two text fields a sheet row keeps in its tag. */
    @Suppress("UNCHECKED_CAST")
    private fun fields(row: View) = row.tag as Pair<EditText, EditText>

    private fun removeButton(onClick: () -> Unit) = ui.icon(R.drawable.hex_icon_circle_x, 20, ui.t.muted).apply {
        layoutParams = LinearLayout.LayoutParams(ui.px(40), ui.px(40))
        setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10))
        contentDescription = "Remove"
        setOnClickListener { onClick() }
    }

    private fun preview(input: EditText, output: TextView) {
        val text = input.text.toString().ifEmpty { input.hint.toString() }
        output.text = runCatching { HexCore.process(text, settings.textSteps()) }.getOrElse { "—" }
    }

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun later(block: () -> Unit) = root.postDelayed(block, 350)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

}
