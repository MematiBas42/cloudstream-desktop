package com.lagradost.cloudstream3.ui.quicksearch

import android.app.Activity
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QuickSearchFragmentTest {

    @Test
    fun testTitleSanitizationDubSubEdgeCases() {
        // Exact upstream suffixes: (DUB), (SUB), (Dub), (Sub), (dub), (sub)
        assertEquals("Attack on Titan", QuickSearchFragment.sanitizeQuery("Attack on Titan (DUB)"))
        assertEquals("Naruto Shippuden", QuickSearchFragment.sanitizeQuery("Naruto Shippuden (SUB)"))
        assertEquals("Bleach", QuickSearchFragment.sanitizeQuery("Bleach (Dub)"))
        assertEquals("One Piece", QuickSearchFragment.sanitizeQuery("One Piece (Sub)"))
        assertEquals("Demon Slayer", QuickSearchFragment.sanitizeQuery("Demon Slayer (dub)"))
        assertEquals("Jujutsu Kaisen", QuickSearchFragment.sanitizeQuery("Jujutsu Kaisen (sub)"))
        assertEquals("Death Note", QuickSearchFragment.sanitizeQuery("   Death Note (Sub)   "))
        assertEquals("Solo Leveling", QuickSearchFragment.sanitizeQuery("Solo Leveling"))
        assertEquals("", QuickSearchFragment.sanitizeQuery("   "))
        assertNull(QuickSearchFragment.sanitizeQuery(null))
    }

    @Test
    fun testPushSearchDispatchesSanitizedQueryToEventBus() {
        var receivedQuery: String? = null
        var receivedProviders: Array<String>? = null

        val listener: (Pair<String?, Array<String>?>) -> Unit = { (query, providers) ->
            receivedQuery = query
            receivedProviders = providers
        }

        QuickSearchFragment.quickSearchEvent += listener

        try {
            QuickSearchFragment.pushSearch("Frieren: Beyond Journey's End (Dub)", arrayOf("AllAnime", "GogoAnime"))
            assertEquals("Frieren: Beyond Journey's End", receivedQuery)
            assertNotNull(receivedProviders)
            assertEquals(2, receivedProviders?.size)
            assertEquals("AllAnime", receivedProviders?.get(0))
            assertEquals("GogoAnime", receivedProviders?.get(1))
        } finally {
            QuickSearchFragment.quickSearchEvent -= listener
        }
    }

    @Test
    fun testPushSearchActivityOverloadParity() {
        var receivedQuery: String? = null
        val listener: (Pair<String?, Array<String>?>) -> Unit = { (query, _) ->
            receivedQuery = query
        }

        QuickSearchFragment.quickSearchEvent += listener

        try {
            val dummyActivity = Activity()
            QuickSearchFragment.pushSearch(dummyActivity, "Steins;Gate (SUB)", null)
            assertEquals("Steins;Gate", receivedQuery)
        } finally {
            QuickSearchFragment.quickSearchEvent -= listener
        }
    }

    @Test
    fun testConstantsAndClickCallbackLifecycle() {
        assertEquals("autosearch", QuickSearchFragment.AUTOSEARCH_KEY)
        assertEquals("providers", QuickSearchFragment.PROVIDER_KEY)

        var clicked = false
        val callback: (SearchClickCallback) -> Unit = { clicked = true }
        QuickSearchFragment.clickCallback = callback
        assertNotNull(QuickSearchFragment.clickCallback)

        val fragment = QuickSearchFragment()
        fragment.onDestroy()
        assertNull(QuickSearchFragment.clickCallback)
    }
}
