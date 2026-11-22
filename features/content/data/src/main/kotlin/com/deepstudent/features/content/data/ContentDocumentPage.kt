package com.deepstudent.features.content.data

import com.deepstudent.features.content.api.ContentDocument

internal object ContentDocumentPage {
    fun render(document: ContentDocument, theme: com.deepstudent.features.content.api.ContentTheme): String {
        require(listOf(theme.background, theme.foreground, theme.accent).all { Regex("#[0-9a-fA-F]{6}").matches(it) })
        val body = when (document) {
            is ContentDocument.Formula -> "<div data-dstu-formula data-display=\"${document.display}\">${escape(document.source)}</div>"
            is ContentDocument.Diagram -> "<div data-dstu-diagram>${escape(document.source)}</div>"
            is ContentDocument.HtmlCard -> document.html
        }
        require(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        val csp = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self'; media-src 'self'; connect-src 'none'; " +
            "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        val mode = if (theme.dark) "dark" else "light"
        val night = if (theme.dark) " class=\"night_mode\"" else ""
        return """<!doctype html><html$night><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <link rel="stylesheet" href="/vendor/katex.min.css">
            <style>:root{--background:${theme.background};--foreground:${theme.foreground};--primary:${theme.accent};color-scheme:$mode} html,body{margin:0;background:var(--background);color:var(--foreground)} body{padding:12px;overflow-wrap:anywhere}</style>
            <script src="/host.js"></script><script src="/vendor/katex.min.js"></script>
            <script src="/vendor/mermaid.min.js"></script></head><body$night>$body</body></html>""".trimIndent()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
