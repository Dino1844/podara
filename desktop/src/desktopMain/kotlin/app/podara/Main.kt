package app.podara

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.podara.platform.SingleInstanceLock
import app.podara.platform.WindowGeometry
import app.podara.util.Logger
import app.podara.util.Strings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

private val appIcon = lazy {
    val bytes = object {}::class.java.classLoader.getResourceAsStream("logo-256.png")!!.readBytes()
    BitmapPainter(bytes.decodeToImageBitmap())
}

fun main() {
    val logFile = File(System.getProperty("user.home"), ".podara/crash.log")

    // Claim the instance before touching the database. A second copy would
    // otherwise open the same SQLite file and fail schema creation with
    // "database is locked", crashing on startup.
    val instanceLock = SingleInstanceLock.acquire()
    if (instanceLock == null) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                null,
                "Podara is already running.\n\nLook for it in the system tray.",
                "Podara",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        return
    }

    Runtime.getRuntime().addShutdownHook(Thread { instanceLock.release() })

    try {
        application {
            val windowState = rememberWindowState(
                size = DpSize(1200.dp, 800.dp),
                position = WindowPosition(Alignment.Center)
            )
            var closeRequestCount by remember { mutableIntStateOf(0) }

            Window(
                onCloseRequest = { closeRequestCount++ },
                state = windowState,
                title = Strings["app_name"],
                undecorated = true,
                transparent = false,
                icon = appIcon.value
            ) {
                // An undecorated window has no OS-imposed floor, and the layout
                // does not shrink fixed-size controls — it squeezes the elastic
                // text columns beside them until they vanish. See WindowGeometry
                // for how the numbers are derived.
                //
                // Assigned in SideEffect, not in the composition body: a plain
                // assignment there is a side effect that would run on every
                // recomposition, the same reason App.kt wraps its tray hook.
                val density = LocalDensity.current
                SideEffect {
                    window.minimumSize = WindowGeometry.minimumSizePx(density)
                }

                App(windowState, window, closeRequestCount)
            }
        }
    } catch (e: Exception) {
        logFile.parentFile?.mkdirs()
        logFile.appendText(
            "[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}] FATAL: ${e.message}\n${e.stackTraceToString()}\n\n"
        )
        throw e
    } finally {
        instanceLock.release()
    }
}
