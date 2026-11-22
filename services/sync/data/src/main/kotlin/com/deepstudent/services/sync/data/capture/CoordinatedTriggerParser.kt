package com.deepstudent.services.sync.data.capture

/** Only called on verified frozen SQL or the separately verified coordinated repair SQL. */
internal object CoordinatedTriggerParser {
    private val statements = Regex("(?ims)^CREATE\\s+TRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\n.*?^END;")
    private val header = Regex("(?is)^CREATE\\s+TRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s+(?:BEFORE|AFTER|INSTEAD\\s+OF)\\s+(INSERT|UPDATE|DELETE)(?:\\s+OF\\s+.*?)?\\s+ON\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
    private val ifNotExists = Regex("(?i)^CREATE\\s+TRIGGER\\s+IF\\s+NOT\\s+EXISTS\\s+")
    private val capture = Regex("(?i)\\bINSERT\\s+INTO\\s+__change_log\\b")
    private val starts = Regex("(?im)^CREATE\\s+TRIGGER\\b")

    fun parse(sql: String): List<Pair<String, FrozenTrigger>> {
        val matches = statements.findAll(sql).toList()
        check(matches.size == starts.findAll(sql).count())
        return matches.map { match ->
            val text = match.value
            val parts = checkNotNull(header.find(text))
            // SQLite stores CREATE TRIGGER without IF NOT EXISTS or the terminating semicolon.
            val stored = text.replaceFirst(ifNotExists, "CREATE TRIGGER ").dropLast(1)
            parts.groupValues[2] to FrozenTrigger(match.groupValues[1], operation(parts.groupValues[1]),
                capture.containsMatchIn(text), sha256(stored.toByteArray(Charsets.UTF_8)))
        }
    }
}
