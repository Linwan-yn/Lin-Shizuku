package moe.shizuku.privileged.api.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * A styled character in the terminal buffer.
 */
data class StyledChar(
    val char: Char,
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false
) {
    fun toSpanStyle(defaultFg: Color, defaultBg: Color): SpanStyle {
        val finalFg = if (inverse) bg ?: defaultBg else fg ?: defaultFg
        val finalBg = if (inverse) fg ?: defaultFg else bg ?: defaultBg
        return SpanStyle(
            color = finalFg,
            background = finalBg,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (underline) TextDecoration.Underline else null
        )
    }
}

/**
 * Current SGR (Select Graphic Rendition) state.
 */
data class SgrState(
    var fg: Color? = null,
    var bg: Color? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false
) {
    fun reset() {
        fg = null; bg = null; bold = false; italic = false; underline = false; inverse = false
    }
    fun toStyledChar(c: Char) = StyledChar(c, fg, bg, bold, italic, underline, inverse)
}

/**
 * Terminal buffer with ANSI/VT100 escape sequence parsing.
 * Maintains a character grid and cursor position.
 */
class TerminalBuffer(
    private val defaultFg: Color = Color(0xFFE0E0E0),
    private val defaultBg: Color = Color.Transparent,
    var maxRows: Int = 200,
    var cols: Int = 100
) {
    private val rows = mutableListOf<MutableList<StyledChar>>()
    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private val sgr = SgrState()
    private var scrollBack = mutableListOf<List<StyledChar>>()

    init {
        ensureRow(0)
    }

    private fun ensureRow(row: Int) {
        while (rows.size <= row) {
            rows.add(mutableListOf())
        }
    }

    private fun ensureCol(row: Int, col: Int) {
        val r = rows[row]
        while (r.size <= col) {
            r.add(StyledChar(' '))
        }
    }

    /** Feed raw bytes into the terminal buffer. */
    fun feed(data: ByteArray) {
        feed(String(data, Charsets.UTF_8))
    }

    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u001B' && i + 1 < text.length -> {
                    i = parseEscape(text, i + 1)
                }
                c == '\r' -> { cursorCol = 0; i++ }
                c == '\n' -> { newline(); i++ }
                c == '\b' -> { if (cursorCol > 0) cursorCol--; i++ }
                c == '\t' -> { cursorCol = ((cursorCol / 8) + 1) * 8; i++ }
                c == '\u0007' -> { i++ } // bell, ignore
                c.code < 32 -> { i++ } // other control chars, ignore
                else -> {
                    putChar(c)
                    i++
                }
            }
        }
    }

    private fun putChar(c: Char) {
        ensureRow(cursorRow)
        ensureCol(cursorRow, cursorCol)
        rows[cursorRow][cursorCol] = sgr.toStyledChar(c)
        cursorCol++
        if (cursorCol >= cols) {
            // Auto-wrap
            cursorCol = 0
            newline()
        }
    }

    private fun newline() {
        cursorRow++
        cursorCol = 0
        if (cursorRow >= maxRows) {
            // Scroll: move top row to scrollback
            if (rows.isNotEmpty()) {
                scrollBack.add(rows.removeAt(0).toList())
                if (scrollBack.size > 1000) scrollBack.removeAt(0)
            }
            cursorRow = maxRows - 1
        }
        ensureRow(cursorRow)
    }

    private fun parseEscape(text: String, start: Int): Int {
        if (start >= text.length) return start
        return when (text[start]) {
            '[' -> parseCsi(text, start + 1)
            ']' -> parseOsc(text, start + 1)
            '(' -> start + 2 // charset, ignore
            ')' -> start + 2
            '>' -> start + 1 // numeric keypad, ignore
            '=' -> start + 1
            'M' -> { reverseIndex(); start + 1 }
            'D' -> { newline(); start + 1 } // line feed
            'E' -> { cursorCol = 0; newline(); start + 1 }
            'H' -> { cursorCol = ((cursorCol / 8) + 1) * 8; start + 1 } // tab set, ignore
            '7' -> start + 1 // save cursor, ignore
            '8' -> start + 1 // restore cursor, ignore
            else -> start + 1
        }
    }

    private fun reverseIndex() {
        if (cursorRow > 0) cursorRow--
        else {
            // Insert blank line at top
            rows.add(0, mutableListOf())
            if (rows.size > maxRows) rows.removeAt(rows.size - 1)
        }
    }

    private fun parseCsi(text: String, start: Int): Int {
        var i = start
        val params = mutableListOf<String>()
        var current = StringBuilder()
        var private = false

        if (i < text.length && text[i] == '?') { private = true; i++ }

        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9') {
                current.append(c)
                i++
            } else if (c == ';') {
                params.add(current.toString())
                current = StringBuilder()
                i++
            } else {
                params.add(current.toString())
                return executeCsi(c, params, private, i + 1)
            }
        }
        return i
    }

    private fun executeCsi(cmd: Char, params: List<String>, private: Boolean, next: Int): Int {
        val nums = params.map { it.toIntOrNull() ?: 0 }
        when (cmd) {
            'A' -> cursorRow = maxOf(0, cursorRow - (nums[0].takeIf { it > 0 } ?: 1))
            'B' -> { cursorRow += nums[0].takeIf { it > 0 } ?: 1; ensureRow(cursorRow) }
            'C' -> cursorCol += nums[0].takeIf { it > 0 } ?: 1
            'D' -> cursorCol = maxOf(0, cursorCol - (nums[0].takeIf { it > 0 } ?: 1))
            'E' -> { cursorRow += nums[0].takeIf { it > 0 } ?: 1; cursorCol = 0; ensureRow(cursorRow) }
            'F' -> { cursorRow = maxOf(0, cursorRow - (nums[0].takeIf { it > 0 } ?: 1)); cursorCol = 0 }
            'G' -> cursorCol = maxOf(0, (nums[0].takeIf { it > 0 } ?: 1) - 1)
            'H', 'f' -> {
                cursorRow = maxOf(0, (nums.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1)
                cursorCol = maxOf(0, (nums.getOrNull(1)?.takeIf { it > 0 } ?: 1) - 1)
                ensureRow(cursorRow)
            }
            'J' -> {
                when (nums[0]) {
                    0 -> { // clear from cursor to end
                        if (cursorRow < rows.size) {
                            while (rows[cursorRow].size > cursorCol) rows[cursorRow].removeAt(cursorCol)
                        }
                        while (rows.size > cursorRow + 1) rows.removeAt(rows.size - 1)
                    }
                    1 -> { // clear from start to cursor
                        for (r in 0 until cursorRow) rows[r].clear()
                        if (cursorRow < rows.size) {
                            for (c in 0..minOf(cursorCol, rows[cursorRow].size - 1)) {
                                rows[cursorRow][c] = StyledChar(' ')
                            }
                        }
                    }
                    2, 3 -> { // clear entire screen
                        rows.clear()
                        scrollBack.clear()
                        cursorRow = 0; cursorCol = 0
                        ensureRow(0)
                    }
                }
            }
            'K' -> {
                when (nums[0]) {
                    0 -> { if (cursorRow < rows.size) while (rows[cursorRow].size > cursorCol) rows[cursorRow].removeAt(cursorCol) }
                    1 -> { if (cursorRow < rows.size) for (c in 0..minOf(cursorCol, rows[cursorRow].size - 1)) rows[cursorRow][c] = StyledChar(' ') }
                    2 -> { if (cursorRow < rows.size) rows[cursorRow].clear() }
                }
            }
            'L' -> { // insert lines
                val n = nums[0].takeIf { it > 0 } ?: 1
                repeat(n) { rows.add(cursorRow, mutableListOf()) }
                while (rows.size > maxRows) rows.removeAt(rows.size - 1)
            }
            'M' -> { // delete lines
                val n = nums[0].takeIf { it > 0 } ?: 1
                repeat(n) { if (cursorRow < rows.size) rows.removeAt(cursorRow) }
            }
            'P' -> { // delete chars
                val n = nums[0].takeIf { it > 0 } ?: 1
                if (cursorRow < rows.size) repeat(n) { if (cursorCol < rows[cursorRow].size) rows[cursorRow].removeAt(cursorCol) }
            }
            '@' -> { // insert blanks
                val n = nums[0].takeIf { it > 0 } ?: 1
                if (cursorRow < rows.size) repeat(n) { rows[cursorRow].add(cursorCol, StyledChar(' ')) }
            }
            'X' -> { // erase chars
                val n = nums[0].takeIf { it > 0 } ?: 1
                if (cursorRow < rows.size) for (c in cursorCol until minOf(cursorCol + n, rows[cursorRow].size)) rows[cursorRow][c] = StyledChar(' ')
            }
            'm' -> applySgr(params)
            'r' -> { /* set scrolling region, ignore */ }
            's' -> { /* save cursor, ignore */ }
            'u' -> { /* restore cursor, ignore */ }
            'h', 'l' -> { /* mode set/reset, ignore for now */ }
            'd' -> cursorRow = maxOf(0, (nums[0].takeIf { it > 0 } ?: 1) - 1)
        }
        return next
    }

    private fun applySgr(params: List<String>) {
        if (params.isEmpty() || params[0].isEmpty()) {
            sgr.reset()
            return
        }
        var i = 0
        while (i < params.size) {
            val code = params[i].toIntOrNull() ?: 0
            when (code) {
                0 -> sgr.reset()
                1 -> sgr.bold = true
                2 -> { sgr.bold = false }
                3 -> sgr.italic = true
                4 -> sgr.underline = true
                5, 6 -> { /* blink, ignore */ }
                7 -> sgr.inverse = true
                9 -> { /* strikethrough, ignore */ }
                22 -> sgr.bold = false
                23 -> sgr.italic = false
                24 -> sgr.underline = false
                27 -> sgr.inverse = false
                30 -> sgr.fg = Color(0xFF000000)
                31 -> sgr.fg = Color(0xFFCC0000)
                32 -> sgr.fg = Color(0xFF00CC00)
                33 -> sgr.fg = Color(0xFFCCCC00)
                34 -> sgr.fg = Color(0xFF0000CC)
                35 -> sgr.fg = Color(0xFFCC00CC)
                36 -> sgr.fg = Color(0xFF00CCCC)
                37 -> sgr.fg = Color(0xFFCCCCCC)
                38 -> {
                    if (i + 1 < params.size && params[i + 1] == "5" && i + 2 < params.size) {
                        sgr.fg = palette256(params[i + 2].toIntOrNull() ?: 0)
                        i += 2
                    } else if (i + 3 < params.size && params[i + 1] == "2") {
                        val r = params[i + 2].toIntOrNull() ?: 0
                        val g = params[i + 3].toIntOrNull() ?: 0
                        val b = params[i + 4].toIntOrNull() ?: 0
                        sgr.fg = Color(r, g, b)
                        i += 4
                    }
                }
                39 -> sgr.fg = null
                40 -> sgr.bg = Color(0xFF000000)
                41 -> sgr.bg = Color(0xFFCC0000)
                42 -> sgr.bg = Color(0xFF00CC00)
                43 -> sgr.bg = Color(0xFFCCCC00)
                44 -> sgr.bg = Color(0xFF0000CC)
                45 -> sgr.bg = Color(0xFFCC00CC)
                46 -> sgr.bg = Color(0xFF00CCCC)
                47 -> sgr.bg = Color(0xFFCCCCCC)
                48 -> {
                    if (i + 1 < params.size && params[i + 1] == "5" && i + 2 < params.size) {
                        sgr.bg = palette256(params[i + 2].toIntOrNull() ?: 0)
                        i += 2
                    } else if (i + 3 < params.size && params[i + 1] == "2") {
                        val r = params[i + 2].toIntOrNull() ?: 0
                        val g = params[i + 3].toIntOrNull() ?: 0
                        val b = params[i + 4].toIntOrNull() ?: 0
                        sgr.bg = Color(r, g, b)
                        i += 4
                    }
                }
                49 -> sgr.bg = null
                90 -> sgr.fg = Color(0xFF555555)
                91 -> sgr.fg = Color(0xFFFF5555)
                92 -> sgr.fg = Color(0xFF55FF55)
                93 -> sgr.fg = Color(0xFFFFFF55)
                94 -> sgr.fg = Color(0xFF5555FF)
                95 -> sgr.fg = Color(0xFFFF55FF)
                96 -> sgr.fg = Color(0xFF55FFFF)
                97 -> sgr.fg = Color(0xFFFFFFFF)
                100 -> sgr.bg = Color(0xFF555555)
                101 -> sgr.bg = Color(0xFFFF5555)
                102 -> sgr.bg = Color(0xFF55FF55)
                103 -> sgr.bg = Color(0xFFFFFF55)
                104 -> sgr.bg = Color(0xFF5555FF)
                105 -> sgr.bg = Color(0xFFFF55FF)
                106 -> sgr.bg = Color(0xFF55FFFF)
                107 -> sgr.bg = Color(0xFFFFFFFF)
            }
            i++
        }
    }

    private fun palette256(index: Int): Color {
        return when {
            index < 16 -> standardColors[index]
            index < 232 -> {
                val i = index - 16
                val r = (i / 36) % 6
                val g = (i / 6) % 6
                val b = i % 6
                val conv = { v: Int -> if (v == 0) 0 else v * 40 + 55 }
                Color(conv(r), conv(g), conv(b))
            }
            else -> {
                val gray = (index - 232) * 10 + 8
                Color(gray, gray, gray)
            }
        }
    }

    private fun parseOsc(text: String, start: Int): Int {
        // OSC sequences end with BEL (\u0007) or ESC \
        var i = start
        while (i < text.length) {
            if (text[i] == '\u0007') return i + 1
            if (text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\') return i + 2
            i++
        }
        return i
    }

    fun clear() {
        rows.clear()
        scrollBack.clear()
        cursorRow = 0
        cursorCol = 0
        sgr.reset()
        ensureRow(0)
    }

    companion object {
        private val standardColors = arrayOf(
            Color(0xFF000000), Color(0xFFCC0000), Color(0xFF00CC00), Color(0xFFCCCC00),
            Color(0xFF0000CC), Color(0xFFCC00CC), Color(0xFF00CCCC), Color(0xFFCCCCCC),
            Color(0xFF555555), Color(0xFFFF5555), Color(0xFF55FF55), Color(0xFFFFFF55),
            Color(0xFF5555FF), Color(0xFFFF55FF), Color(0xFF55FFFF), Color(0xFFFFFFFF)
        )
    }
}
