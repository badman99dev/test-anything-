package com.example.cfapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cfapp.databinding.ActivityMainBinding
import com.ead.lib.cloudflare_bypass.solver.CloudFlareSolver
import com.ead.lib.cloudflare_bypass.solver.SolverConfig
import com.ead.lib.cloudflare_bypass.solver.SolverEvent
import com.ead.lib.cloudflare_bypass.solver.SolverResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var solver: CloudFlareSolver? = null
    private var lastCookieHeader: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        log("═══ SOLVE: $url ═══")

        val s = CloudFlareSolver(
            this,
            SolverConfig(
                timeoutMs = 30_000,
                backgroundSolveMs = 20_000,
            ),
        )
        solver = s

        s.onEvent = { ev ->
            runOnUiThread { log("[${fmt(ev.at)}] ${levelIcon(ev.level)} ${ev.message}") }
        }

        lifecycleScope.launch {
            val result = s.solve(url)
            log("═══ RESULT: ${result::class.simpleName} ═══")
            when (result) {
                is SolverResult.Solved -> {
                    lastCookieHeader = result.cookieHeader
                    binding.resultText.text =
                        "✅ Solved — ${result.challengeType} · ${result.elapsedMs}ms · " +
                            "taps=${s.tapCount}\nTTL=${result.cfClearanceTtlSeconds ?: "n/a"}s " +
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
                is SolverResult.InteractionNeeded -> {
                    binding.resultText.text = "⚠️ Interaction needed (${result.challengeType})"
                    log("Background window expired — challenge needs interaction")
                }
                is SolverResult.Failed -> {
                    binding.resultText.text = "❌ Failed: ${result.reason}"
                    log("Failed: ${result.reason} (${result.elapsedMs}ms, ${result.challengeType})")
                }
                is SolverResult.Cancelled -> {
                    binding.resultText.text = "Canceled"
                }
            }
        }
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
