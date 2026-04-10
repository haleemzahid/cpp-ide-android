package dev.cppide.spike1

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { SpikeRunner(ctx) }

    var output by remember { mutableStateOf("Tap a button to run a validation.\n") }
    var busy by remember { mutableStateOf(false) }

    val nativeHelloPresent = remember { runner.nativeHelloPresent() }
    val helloLibPresent = remember { runner.helloLibPresent() }
    val userLibPresent = remember { runner.userLibPresent() }
    val debugTargetPresent = remember { runner.debugTargetPresent() }
    val clangPresent = remember { runner.clangPresent() }
    val libDirListing = remember { runner.listNativeLibDir() }

    fun append(text: String) {
        output += "\n" + text
    }

    fun run(label: String, block: suspend () -> SpikeRunner.ExecResult) {
        if (busy) return
        busy = true
        scope.launch {
            append("▶ $label")
            val r = block()
            append("  command : ${r.command}")
            append("  exitCode: ${r.exitCode}")
            append("  took    : ${r.durationMs} ms")
            if (r.error != null) append("  ERROR   : ${r.error}")
            if (r.stdout.isNotBlank()) append("  stdout  : ${r.stdout.trim()}")
            if (r.stderr.isNotBlank()) append("  stderr  : ${r.stderr.trim()}")
            append(
                when {
                    !r.ran -> "✗ FAILED (couldn't run)"
                    r.cleanExit -> "✓ OK (clean exit 0)"
                    else -> "✓ RAN (program returned ${r.exitCode})"
                }
            )
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("C++ IDE — Spike 1") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize()
        ) {
            // --- Controls: independently scrollable, gets ~40% of the screen ---
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .verticalScroll(rememberScrollState())
            ) {
                EnvCard(
                    androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
                    nativeLibDir = ctx.applicationInfo.nativeLibraryDir,
                    nativeHelloPresent = nativeHelloPresent,
                    helloLibPresent = helloLibPresent,
                    userLibPresent = userLibPresent,
                    clangPresent = clangPresent,
                    libDirListing = libDirListing,
                )

                Spacer(Modifier.height(8.dp))

                // Grid: two buttons per row, except C on its own row.
                ButtonRow(
                    left = "A. exec nativeLibraryDir" to {
                        run("A. exec from nativeLibraryDir") { runner.runNativeHelloFromNativeLibraryDir() }
                    },
                    leftEnabled = !busy && nativeHelloPresent,
                    right = "B. exec filesDir" to {
                        run("B. copy to filesDir and exec") { runner.copyAndRunFromFilesDir() }
                    },
                    rightEnabled = !busy && nativeHelloPresent,
                )
                Spacer(Modifier.height(6.dp))
                ButtonRow(
                    left = "D. dlopen nativeLibraryDir" to {
                        run("D. System.loadLibrary from nativeLibraryDir") { runner.loadLibraryFromNativeLibDir() }
                    },
                    leftEnabled = !busy && helloLibPresent,
                    right = "E. dlopen filesDir" to {
                        run("E. dlopen from filesDir (critical)") { runner.loadLibraryFromFilesDir() }
                    },
                    rightEnabled = !busy && helloLibPresent,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { run("F. runtime loop: dlopen + JNI + user main + pipe stdout") { runner.runUserProgramShim() } },
                    enabled = !busy && userLibPresent,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("F. Run user main() via dlopen + pipe stdout") }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { run("Install Termux toolchain (extract termux.zip)") { runner.installToolchain() } },
                    enabled = !busy && clangPresent,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install toolchain (extract termux.zip)") }
                Spacer(Modifier.height(6.dp))
                ButtonRow(
                    left = "C. clang --version" to {
                        run("C. clang --version") { runner.runClangVersion() }
                    },
                    leftEnabled = !busy && clangPresent,
                    right = "H. compile hello.cpp" to {
                        run("H. compile + run hello.cpp on device") { runner.compileAndRunHelloCpp() }
                    },
                    rightEnabled = !busy && clangPresent,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { run("I. ptrace spike (fork + exec + ptrace child)") { runner.runPtraceSpike() } },
                    enabled = !busy && debugTargetPresent,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("I. ptrace spike — debugger primitive") }
            }

            Spacer(Modifier.height(10.dp))

            // --- Output header + box: gets the remaining ~60% ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Output", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("running…", fontSize = 11.sp)
                }
                TextButton(
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("spike1 output", output))
                        Toast.makeText(ctx, "copied ${output.length} chars", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("copy") }
                TextButton(onClick = { output = "" }, enabled = !busy) {
                    Text("clear")
                }
            }

            Surface(
                color = Color(0xFF0B0F14),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f)
            ) {
                Text(
                    text = output,
                    color = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun ButtonRow(
    left: Pair<String, () -> Unit>,
    leftEnabled: Boolean,
    right: Pair<String, () -> Unit>,
    rightEnabled: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = left.second,
            enabled = leftEnabled,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        ) { Text(left.first, fontSize = 11.sp, maxLines = 1) }
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = right.second,
            enabled = rightEnabled,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        ) { Text(right.first, fontSize = 11.sp, maxLines = 1) }
    }
}

@Composable
private fun EnvCard(
    androidVersion: String,
    abi: String,
    nativeLibDir: String,
    nativeHelloPresent: Boolean,
    helloLibPresent: Boolean,
    userLibPresent: Boolean,
    clangPresent: Boolean,
    libDirListing: List<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text("Device", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("$androidVersion · $abi", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "nativeLibraryDir:",
                fontWeight = FontWeight.Bold,
            )
            Text(
                nativeLibDir,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "  libnativehello.so: ${if (nativeHelloPresent) "present ✓" else "MISSING"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (nativeHelloPresent) Color(0xFF66BB6A) else Color(0xFFEF5350),
            )
            Text(
                "  libhellolib.so   : ${if (helloLibPresent) "present ✓" else "MISSING"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (helloLibPresent) Color(0xFF66BB6A) else Color(0xFFEF5350),
            )
            Text(
                "  libuser.so       : ${if (userLibPresent) "present ✓" else "MISSING"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (userLibPresent) Color(0xFF66BB6A) else Color(0xFFEF5350),
            )
            Text(
                "  libclang.so      : ${if (clangPresent) "present ✓" else "not bundled (phase 1b)"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (clangPresent) Color(0xFF66BB6A) else Color(0xFF9E9E9E),
            )
            if (libDirListing.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("full listing:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                libDirListing.forEach {
                    Text(
                        "  $it",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
