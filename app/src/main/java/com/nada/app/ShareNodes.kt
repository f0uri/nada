package com.nada.app

import java.util.Locale

/**
 * Pure label matching and step decisions for the Instagram share flow.
 *
 * Deliberately free of Android imports so it can be compiled and tested on a
 * plain JVM. Matching is text / content-description / resource-id based only:
 * nothing here knows about screen geometry, and no caller may turn a match into
 * a positional tap.
 */
object ShareNodes {
    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /** Packages of the Android system share sheet, which may host the Copy link
     *  node once Share was tapped. */
    val RESOLVER_PACKAGES = setOf(
        "com.android.intentresolver",
        "com.google.android.intentresolver",
    )

    /**
     * Whether a window package may be searched in [phase]: Instagram always;
     * the system share-sheet packages only once Share was tapped.
     */
    fun isSearchablePackage(pkg: String?, phase: Int): Boolean {
        if (pkg == INSTAGRAM_PACKAGE) return true
        return phase == PHASE_WAIT_SHEET && pkg != null && pkg in RESOLVER_PACKAGES
    }

    /** Phase 0: looking at the reel, share sheet not requested yet. */
    const val PHASE_FIND_SHARE = 0

    /** Phase 1: Share was tapped once, waiting for the sheet to appear. */
    const val PHASE_WAIT_SHEET = 1

    /** Exact content-descriptions of the reel Share (paper plane) action. */
    val SHARE_DESCRIPTIONS = listOf(
        "share", "share reel", "share post", "share video", "share reels", "share to",
        "send", "send reel", "send post",
        "مشاركة", "مشاركه", "مشاركة الريل", "مشاركة الريلز", "مشاركة المنشور",
        "مشاركة الفيديو", "إرسال", "ارسال",
    )

    /** Prefixes accepted only when no exact description matched. */
    val SHARE_PREFIXES = listOf("share ", "send ", "مشاركة ", "مشاركه ", "إرسال ", "ارسال ")

    /** Resource-id fragments of the reel Share action across Instagram layouts. */
    val SHARE_ID_FRAGMENTS = listOf(
        "row_feed_button_share", "direct_share_button", "direct_share",
        "share_button", "button_share", "action_bar_share", "action_share",
        "reel_share", "share_reel", "feed_button_share",
    )

    /** Exact labels of the share-sheet "Copy link" action. */
    val COPY_DESCRIPTIONS = listOf(
        "copy link", "copy reel link", "copy post link", "copy video link", "copy url",
        "copy link to reel", "copy link to post",
        "نسخ الرابط", "نسخ رابط", "نسخ رابط الريل", "نسخ رابط المنشور",
        "نسخ عنوان url", "نسخ الارتباط", "نسخ الوصلة",
    )

    val COPY_PREFIXES = listOf("copy link", "copy url", "نسخ الرابط", "نسخ رابط", "نسخ عنوان")

    /** Last-resort exact labels, only ever used inside an opened share sheet. */
    val COPY_EXACT_FALLBACKS = listOf("copy", "نسخ")

    val COPY_ID_FRAGMENTS = listOf("copy_link", "copy_url", "action_copy", "copy_button")

    /** Substrings for the system-side node text search (cheap first pass). */
    val SHARE_QUERIES = listOf("Share", "مشاركة", "مشاركه", "إرسال", "ارسال", "Send")

    val COPY_QUERIES = listOf(
        "Copy link", "Copy URL", "نسخ الرابط", "نسخ رابط", "نسخ عنوان", "Copy", "نسخ",
    )

    /** Trims, lowercases and normalises spacing/Arabic presentation forms. */
    fun normalize(raw: String?): String {
        val text = raw ?: return ""
        // Tatweel (U+0640) is pure letter elongation: drop it, do not split the word.
        return text.replace("\u0640", "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('\u00a0', ' ')
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '…', ':', '،', ',')
            .trim()
    }

    /** 3 = exact description, 2 = resource id, 1 = prefix, 0 = no match. */
    fun shareScore(description: String?, viewId: String?): Int {
        val desc = normalize(description)
        val id = (viewId ?: "").lowercase(Locale.US)
        if (desc.isNotEmpty() && desc in SHARE_DESCRIPTIONS) return 3
        if (id.isNotEmpty() && SHARE_ID_FRAGMENTS.any { id.contains(it) }) return 2
        if (desc.isNotEmpty() && SHARE_PREFIXES.any { desc.startsWith(it) }) return 1
        return 0
    }

    /** 4 = exact label, 3 = resource id, 2 = prefix, 1 = bare "Copy", 0 = no match. */
    fun copyScore(text: String?, description: String?, viewId: String?): Int {
        val both = listOf(normalize(text), normalize(description)).filter { it.isNotEmpty() }
        val id = (viewId ?: "").lowercase(Locale.US)
        if (both.any { it in COPY_DESCRIPTIONS }) return 4
        if (id.isNotEmpty() && COPY_ID_FRAGMENTS.any { id.contains(it) }) return 3
        if (both.any { label -> COPY_PREFIXES.any { label.startsWith(it) } }) return 2
        if (both.any { it in COPY_EXACT_FALLBACKS }) return 1
        return 0
    }

    /**
     * The whole branching rule of the automation, in one testable place.
     *
     * Only high-confidence matches are ever clicked: an exact label (4/3) or a
     * resource-id (3/2). A weak match is never clicked — in the share sheet a
     * wrong tap could send the reel to a person, so when the label is not
     * exactly "Copy link" / "نسخ الرابط" (or its resource-id), the service
     * waits and then falls back to the clipboard instead of guessing. The same
     * applies to Share on the reel: exact description or resource-id only.
     * Nothing else is ever clicked.
     */
    fun nextAction(phase: Int, copyScore: Int, shareScore: Int): Step = when {
        phase == PHASE_FIND_SHARE && copyScore >= 3 -> Step.COPY
        phase == PHASE_FIND_SHARE && shareScore >= 2 -> Step.SHARE
        phase == PHASE_FIND_SHARE -> Step.GIVE_UP
        phase == PHASE_WAIT_SHEET && copyScore >= 3 -> Step.COPY
        phase == PHASE_WAIT_SHEET -> Step.WAIT
        else -> Step.GIVE_UP
    }

    enum class Step { SHARE, COPY, WAIT, GIVE_UP }
}
