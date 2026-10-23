package app.podara.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's click, focus-ring and hand-cursor behaviour lives in three shared
 * helpers in [app.podara.util], not at the call sites.
 *
 * That is the whole design: `clickableWithoutIndication` and friends draw the
 * pressed feedback, decide whether the focus ring appears, and — since the
 * cursor pass — put the hand pointer on anything they make clickable. A call
 * site that reaches past them for `.clickable()` gets no hand cursor, and a
 * call site that reaches for `pointerHoverIcon` itself is one more place where
 * "disabled controls must not offer a hand" has to be remembered. Both failures
 * are invisible until someone runs the app and notices one button feels wrong.
 *
 * So the invariant is checked against the source text. It is the only way to
 * test a rule that is about *where code is allowed to be written*.
 */
class InteractionCoverageTest {

    private val mainSources: List<File>
        get() = sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The directory holding `desktopMain`, found by walking up from the test working directory. */
    private fun sourceRoot(): File {
        val candidates = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "desktop/src/desktopMain/kotlin") }
            .firstOrNull { it.isDirectory }
        assertTrue(
            "Could not find desktop/src/desktopMain/kotlin from ${File("").absolutePath}; " +
                "this test checks the source tree and cannot run without it.",
            candidates != null
        )
        return candidates!!
    }

    /** Lines that are code, not prose — the docs in this project explain the rules they follow. */
    private fun codeLines(file: File): Sequence<Pair<Int, String>> =
        file.readLines().mapIndexed { index, line -> (index + 1) to line.trim() }
            .asSequence()
            .filter { (_, line) -> !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*") }

    /**
     * `pointerHoverIcon` is what actually puts the hand on the pointer, and
     * [app.podara.util.handCursor] is the only thing allowed to call it. The
     * helper's own import and definition are excluded; everything else is a
     * hand-written cursor at a call site, which is the thing the cursor pass
     * removed 65 of.
     */
    @Test
    fun handCursorIsOnlyEverSetByTheSharedHelper() {
        val offenders = mainSources.flatMap { file ->
            codeLines(file)
                .filter { (_, line) -> line.contains("pointerHoverIcon") }
                .filter { (_, line) -> !line.startsWith("import ") }
                .filter { (_, line) -> file.name != "Interactions.kt" }
                .map { (lineNumber, line) -> "${file.path}:$lineNumber ${line.take(70)}" }
        }
        assertTrue(
            "hand cursor set outside util/Interactions.kt — use Modifier.handCursor(enabled) " +
                "or a shared clickable helper instead:\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    /**
     * Every click goes through a shared helper. Material's own buttons are
     * exempt because they bring their own indication; this test is about the
     * hand-rolled chains, where a bare `.clickable()` silently opts out of the
     * app's pressed feedback, focus ring and cursor.
     */
    @Test
    fun noClickIsWiredWithoutASharedHelper() {
        val offenders = mainSources.flatMap { file ->
            codeLines(file)
                .filter { file.name != "Interactions.kt" }
                .filter { (_, line) -> BareClickable.containsMatchIn(line) }
                .map { (lineNumber, line) -> "${file.path}:$lineNumber ${line.take(70)}" }
        }
        assertTrue(
            "Bare .clickable( found — use clickableWithoutIndication, " +
                "clickableWithoutIndicationOrFocusRing or clickableWithFocusIndicator so the " +
                "pressed state, focus ring and hand cursor stay together:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * The cursor pass left the helpers owning the hand pointer, so a control is
     * either clickable through one of them or not clickable at all. This is the
     * sentence that keeps the next person from re-adding a per-site cursor.
     */
    @Test
    fun theSharedHelpersAreStillThereToUse() {
        val helper = File(sourceRoot(), "app/podara/util/Interactions.kt")
        assertTrue("${helper.path} vanished — the click helpers are the rule this file enforces", helper.isFile)
        val text = helper.readText()
        assertTrue(text.contains("fun Modifier.handCursor"))
        assertTrue(text.contains("clickableWithoutIndication"))
        assertTrue(text.contains("clickableWithFocusIndicator"))
    }

    private companion object {
        /**
         * `clickable(` — and nothing longer. The shared helpers are all named
         * `clickableWith*`, so the opening paren is what tells a helper call
         * from a hand-wired chain.
         */
        val BareClickable = Regex("""\bclickable\(""")
    }
}
