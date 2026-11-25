package com.deepstudent.app.sync

import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.data.apply.IncomingDomain

/** Dependency-ordered domain registration. Every entry is checked again against the frozen registry. */
internal object MainIncomingDomains {
    val vfs = listOf(
        IncomingDomain(
            SharedDatabase.VFS,
            "content",
            listOf(
                "exam_sheets",
                "translations",
                "essays",
                "essay_sessions",
                "mindmaps",
                "questions",
                "answer_submissions",
            ),
        ),
        IncomingDomain(
            SharedDatabase.VFS,
            "question-bank",
            listOf(
                "qbank_sources",
                "qbank_materials",
                "qbank_groups",
                "qbank_group_materials",
                "qbank_papers",
                "qbank_practice_attempts",
            ),
        ),
        IncomingDomain(SharedDatabase.VFS, "review", listOf("review_plans", "mastery_events")),
        IncomingDomain(
            SharedDatabase.VFS,
            "insights",
            listOf(
                "insights",
                "insight_revisions",
                "insight_evidence",
                "insight_relations",
                "insight_events",
                "insight_jobs",
            ),
        ),
        IncomingDomain(SharedDatabase.VFS, "todo", listOf("todo_lists", "todo_items", "pomodoro_records")),
        IncomingDomain(SharedDatabase.VFS, "study-space", listOf("vfs_study_space_profiles", "vfs_space_mounts", "vfs_anchor_edges")),
        IncomingDomain(SharedDatabase.VFS, "media", listOf("portable_media_events")),
        IncomingDomain(SharedDatabase.VFS, "plugin-data", listOf("portable_plugin_records")),
    )
    val chat = listOf(
        IncomingDomain(
            SharedDatabase.CHAT_V2,
            "chat",
            listOf(
                "resources", "chat_v2_session_groups", "chat_v2_sessions", "chat_v2_messages", "chat_v2_blocks",
                "chat_v2_attachments", "chat_v2_session_mistakes", "workspace_index", "chat_v2_session_tags", "chat_v2_compactions",
            ),
        ),
    )
    val mistakes = listOf(
        IncomingDomain(
            SharedDatabase.MISTAKES,
            "cards",
            listOf(
                "mistakes", "document_tasks", "review_sessions", "review_analyses", "custom_anki_templates", "anki_decks",
                "chat_messages", "review_chat_messages", "review_session_mistakes", "anki_cards", "fsrs_card_states", "fsrs_review_logs",
            ),
        ),
    )
    val usage = listOf(IncomingDomain(SharedDatabase.LLM_USAGE, "usage", listOf("llm_usage_logs")))
}
