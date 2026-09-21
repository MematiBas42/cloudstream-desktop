package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@Composable
fun ComposeCategoryGridScreen(
    navController: NavController,
    provider: MainAPI,
    title: String,
    items: List<SearchResponse>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = TvDimensions.ScreenHorizontalPadding,
                vertical = 16.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { navController.goBack() },
                modifier = Modifier.focusable(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = TvTypography.Headline,
                color = Color.White,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TvDimensions.CardWidth),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size) { index ->
                val item = items[index]
                val subtitleText = when (item) {
                    is MovieSearchResponse -> item.year?.toString()
                    is TvSeriesSearchResponse -> item.year?.toString()
                    else -> null
                }

                TvCard(
                    title = item.name,
                    posterUrl = item.posterUrl,
                    subtitle = subtitleText,
                    modifier = Modifier.focusable(),
                    onClick = {
                        navController.navigate(Screen.Details(provider, item.url, item.name, item.posterUrl, null))
                    },
                )
            }
        }
    }
}
