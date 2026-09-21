package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.ui.result.LinearListLayoutComposeAdapter

/**
 * Compose Desktop adapter view that renders a [LazyColumn] (vertical) or [LazyRow] (horizontal)
 * guided by the bridged Android RecyclerView [LinearListLayoutComposeAdapter] parameters.
 */
@Composable
fun LinearListLayoutBridgeView(
    adapter: LinearListLayoutComposeAdapter,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = adapter.initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = adapter.initialFirstVisibleItemScrollOffset
    ),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit
) {
    if (adapter.isHorizontal) {
        LazyRow(
            modifier = modifier,
            state = state,
            reverseLayout = adapter.reverseLayout,
            contentPadding = contentPadding,
            content = content
        )
    } else {
        LazyColumn(
            modifier = modifier,
            state = state,
            reverseLayout = adapter.reverseLayout,
            contentPadding = contentPadding,
            content = content
        )
    }
}
