package com.example.cfapp

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cfapp.databinding.ActivityMainBinding
import com.ead.lib.cloudflare_bypass.solver.CloudFlareSolver
import com.ead.lib.cloudflare_bypass.solver.SolverConfig
import com.ead.lib.cloudflare_bypass.solver.SolverEvent
import com.ead.lib.cloudflare_bypass.solver.SolverResult
import com.ead.lib.cloudflare_bypass.solver.WidgetPopup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { SILENT, POPUP, MANUAL }

    private lateinit var binding: ActivityMainBinding
    private var solver: CloudFlareSolver? = null
    private var lastCookieHeader: String? = null
    private var mode: Mode = Mode.SILENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            mode = when (checkedId) {
                R.id.modeSilent -> Mode.SILENT
                R.id.modePopup  -> Mode.POPUP
                R.id.modeManual -> Mode.MANUAL
                else -> Mode.SILENT
            }
            log("Mode → $mode")
        }

        binding.solveBtn.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isEmpty()) {
                toast("Enter a URL first")
                return@setOnClickListener
            }
            startSolve(url)
        }

        binding.copyBtn.setOnClickListener {
            val header = lastCookieHeader
            if (header == null) {
                toast("Nothing solved yet")
            } else {
                toast("cf_clearance found ✓")
                log("\nCOOKIE HEADER:\n$header")
            }
        }
    }

    override fun onDestroy() {
        solver?.cancel()
        super.onDestroy()
    }

    private fun startSolve(url: String) {
        solver?.cancel()
        binding.resultText.text = ""
        binding.console.text = ""
        lastCookieHeader = null
        binding.widgetContainer.visibility = View.INVISIBLE
        binding.widgetContainer.layoutParams.height = (100 * resources.displayMetrics.density).toInt()

        log("═══ SOLVE: $url ═══")
        log("Mode: $mode")

        val config = when (mode) {
            Mode.SILENT -> SolverConfig(
                timeoutMs = 30_000,
                backgroundSolveMs = 20_000,
                autoCreateContainer = true,  // library injects invisible container itself
                attachContainer   = null,
            )
            Mode.POPUP -> SolverConfig(
                timeoutMs = 30_000,
                backgroundSolveMs = 20_000,
                autoCreateContainer = true,
                widgetPopup = WidgetPopup(
                    position        = WidgetPopup.Position.BOTTOM_CENTER,
                    heightDp        = 110,
                    marginDp        = 24,
                    cornerRadiusDp  = 16,
                    elevationDp     = 10,
                    backgroundColor = Color.WHITE,
                    borderColor     = Color.LTGRAY,
                    borderWidthDp   = 1,
                ),
                attachContainer   = null,
            )
            Mode.MANUAL -> SolverConfig(
                timeoutMs = 30_000,
                backgroundSolveMs = 20_000,
                autoCreateContainer = false,  // use our own explicit container
                attachContainer   = binding.widgetContainer,
            )
        }

        val s = CloudFlareSolver(this, config)
        solver = s

        s.onWidgetSize = { wPx, hPx ->
            val density = resources.displayMetrics.density
            runOnUiThread {
                if (hPx > 0 && mode == Mode.MANUAL) {
                    binding.widgetContainer.visibility = View.VISIBLE
                    binding.widgetContainer.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    binding.widgetContainer.layoutParams.height =
                        maxOf(hPx, (48 * density).roundToInt())
                }
            }
        }
        s.onEvent = { ev ->
            runOnUiThread { log("[${fmt(ev.at)}] ${levelIcon(ev.level)} ${ev.message}") }
        }

        lifecycleScope.launch {
            val result = s.solve(url)
            log("═══ RESULT: ${result::class.simpleName} ═══")
            when (result) {
                is SolverResult.Solved -> renderSolved(result)
                is SolverResult.InteractionNeeded -> {
                    binding.resultText.text = "⚠️ Interaction needed (${result.challengeType})"
                    log("Solver returned InteractionNeeded; attach + await")
                    if (mode != Mode.MANUAL) {
                        log("Switching the box overlay on and attaching widget")
                        binding.widgetContainer.visibility = View.VISIBLE
                    }
                    if (result.solver.attach(binding.widgetContainer)) {
                        log("Widget attached → auto-clicking…")
                        val ar = result.solver.awaitResult()
                        log("═══ INTERACTIVE RESULT: ${ar::class.simpleName} ═══")
                        when (ar) {
                            is SolverResult.Solved -> renderSolved(ar, interactive = true)
                            is SolverResult.Failed -> {
                                binding.resultText.text = "❌ Interactive failed: ${ar.reason}"
                                log("Interactive failed: ${ar.reason}")
                            }
                            else -> log("Interactive done: ${ar::class.simpleName}")
                        }
                    } else {
                        log("attach() returned false — not in INTERACTING/NEEDS_INTERACTION window")
                    }
                }
                is SolverResult.Failed -> {
                    binding.resultText.text = "❌ Failed: ${result.reason}"
                    log("Failed: ${result.reason} (${result.elapsedMs}ms, ${result.challengeType})")
                }
                is SolverResult.Cancelled -> binding.resultText.text = "Canceled"
            }
        }
    }

    private fun renderSolved(result: SolverResult.Solved, interactive: Boolean = false) {
        lastCookieHeader = result.cookieHeader
        val tag = if (interactive) " (interactive)" else ""
        binding.resultText.text =
            "✅ Solved$tag — ${result.challengeType} · ${result.elapsedMs}ms · " +
                "taps=${solver?.tapCount ?: 0}\nTTL=${result.cfClearanceTtlSeconds ?: "n/a"}s " +
                if (result.cfClearanceTtlEstimated) "(est)" else ""
        log("Challenge: ${result.challengeType}")
        log("Elapsed: ${result.elapsedMs}ms | Interacted: ${result.interacted}")
        log("Final URL: ${result.finalUrl}")
        result.cookies.forEach { c ->
            log("🍪 ${c.name}=${c.value.take(30)}… ttl=${c.ttlSeconds ?: "?"}s" +
                if (c.ttlEstimated) " (est)" else "")
        }
        log("Header ready → tap 'Copy cookie header'")
    }

    private fun levelIcon(level: String): String = when (level) {
        SolverEvent.OK -> "✅"
        SolverEvent.ERR -> "❌"
        SolverEvent.TAP -> "👆"
        else -> "ℹ️"
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun log(msg: String) {
        val cur = binding.console.text
        binding.console.text = if (cur.isEmpty()) msg else "$cur\n$msg"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
