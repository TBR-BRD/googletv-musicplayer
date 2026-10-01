@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package de.tbrbd.onradiotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.tbrbd.onradiotv.model.Station
import de.tbrbd.onradiotv.model.WeatherState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val BgColor = Color(0xFF080B14)
private val PanelColor = Color(0xFF10152E)
private val AccentColor = Color(0xFF7AB6FF)
private val FocusColor = Color(0xFFFFD166)
private val MutedColor = Color(0xFFB3BDD6)
private val TextColor = Color(0xFFF6F8FF)
private val ScrimColor = Color(0xCC05070D)

private val ENTER_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

@Composable
fun TvScreen(
    state: TvUiState,
    onSelectStation: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    lastStationForGroup: (String) -> String?,
) {
    var isPickerOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            CoverColumn(coverUrl = state.coverUrl, modifier = Modifier.weight(0.42f).fillMaxHeight())
            SideColumn(
                state = state,
                onOpenPicker = { isPickerOpen = true },
                modifier = Modifier.weight(0.58f).fillMaxHeight(),
            )
        }

        if (isPickerOpen) {
            StationPickerOverlay(
                stations = state.stations,
                currentStationId = state.currentStationId,
                favoriteIds = state.favoriteIds,
                lastStationForGroup = lastStationForGroup,
                onSelect = { id ->
                    onSelectStation(id)
                    isPickerOpen = false
                },
                onToggleFavorite = onToggleFavorite,
                onDismiss = { isPickerOpen = false },
            )
        }
    }
}

@Composable
private fun CoverColumn(coverUrl: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp)),
            color = PanelColor,
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "Albumcover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SideColumn(
    state: TvUiState,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentStation = state.stations.find { it.id == state.currentStationId }

    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Header(stationName = currentStation?.name ?: "OnRadio TV")

            Text(
                text = state.nowPlaying?.title ?: "Noch kein Titel",
                color = TextColor,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = state.nowPlaying?.artist ?: "Bitte einen Sender auswählen.",
                color = MutedColor,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )

            WeatherPanel(weather = state.weather, modifier = Modifier.padding(top = 32.dp))
        }

        StationSelectorButton(
            stationName = currentStation?.name ?: "Sender wählen",
            onOpen = onOpenPicker,
        )
    }
}

@Composable
private fun Header(stationName: String) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
    val dateFormat = remember { SimpleDateFormat("EEEE, dd.MM.", Locale.GERMANY) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = stationName, color = AccentColor, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Column(horizontalAlignment = Alignment.End) {
            Text(text = timeFormat.format(now), color = TextColor, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(text = dateFormat.format(now), color = MutedColor, fontSize = 16.sp)
        }
    }
}

@Composable
private fun WeatherPanel(weather: WeatherState?, modifier: Modifier = Modifier) {
    if (weather == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = weather.temperatureC?.let { "${it.toInt()}°" } ?: "--°",
                    color = TextColor,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "  ${weather.condition}",
                    color = MutedColor,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                weather.days.take(3).forEach { day ->
                    Column {
                        Text(text = day.label, color = MutedColor, fontSize = 14.sp)
                        val max = day.tempMaxC?.let { "${it.toInt()}°" } ?: "--°"
                        val min = day.tempMinC?.let { "${it.toInt()}°" } ?: "--°"
                        Text(text = "$max / $min", color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** The always-visible trigger on the main screen; opens the full picker. */
@Composable
private fun StationSelectorButton(stationName: String, onOpen: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    val borderColor = if (isFocused) FocusColor else Color(0x1AFFFFFF)
    val backgroundColor = if (isFocused) Color(0x29FFD166) else PanelColor

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
                    onOpen()
                    true
                } else {
                    false
                }
            },
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Sender", color = MutedColor, fontSize = 13.sp)
                Text(stationName, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("OK ▾", color = MutedColor, fontSize = 16.sp)
        }
    }

    // The main screen has exactly this one focusable widget, so it should
    // already carry the D-pad focus as soon as the screen appears.
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // Not laid out yet on this frame - harmless, the button is still
            // reachable, it just won't have focus by default this one time.
        }
    }
}

/**
 * Two-pane picker: station "families" (ON Radio, RADIO BOB!, ENERGY, ...) on
 * the left, the stations within the highlighted family on the right. This
 * replaced a single flat list of all 253 stations, which was unusable with a
 * D-pad (up to 253 presses to reach the last one) - the biggest single
 * family now tops out at 67 (RADIO BOB!).
 */
private const val FAVORITES_GROUP = "★ Favoriten"

@Composable
private fun StationPickerOverlay(
    stations: List<Station>,
    currentStationId: String?,
    favoriteIds: Set<String>,
    lastStationForGroup: (String) -> String?,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val focusManager = LocalFocusManager.current
    val favoriteStations = remember(stations, favoriteIds) {
        stations.filter { it.id in favoriteIds }
    }
    val groups = remember(stations, favoriteStations) {
        val base = stations.groupBy { it.group }.map { (name, list) -> name to list }
        if (favoriteStations.isEmpty()) base else listOf(FAVORITES_GROUP to favoriteStations) + base
    }
    val currentGroup = remember(stations, currentStationId) {
        stations.find { it.id == currentStationId }?.group ?: groups.firstOrNull()?.first ?: ""
    }
    var selectedGroup by remember(stations) { mutableStateOf(currentGroup) }
    val groupFocusRequesters = remember(groups) { groups.associate { it.first to FocusRequester() } }

    val currentGroupStations = groups.find { it.first == selectedGroup }?.second ?: emptyList()
    // Prefer the currently playing station if it's in this group, otherwise
    // whatever was last picked here (persisted), otherwise the top entry.
    val preferredStationId = remember(selectedGroup, currentStationId, currentGroupStations) {
        when {
            currentGroupStations.any { it.id == currentStationId } -> currentStationId
            else -> lastStationForGroup(selectedGroup)?.takeIf { id -> currentGroupStations.any { it.id == id } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScrimColor)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        focusManager.moveFocus(FocusDirection.Up); true
                    }
                    Key.DirectionDown -> {
                        focusManager.moveFocus(FocusDirection.Down); true
                    }
                    Key.DirectionLeft -> {
                        focusManager.moveFocus(FocusDirection.Left); true
                    }
                    Key.DirectionRight -> {
                        focusManager.moveFocus(FocusDirection.Right); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight(0.86f)
                .width(880.dp)
                .padding(end = 24.dp),
            color = PanelColor,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Sender wählen", color = TextColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "◀ ▶ Kategorie/Liste · ▲ ▼ navigieren · OK wählt · lang drücken = Favorit · Zurück schließt",
                    color = MutedColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )

                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    GroupList(
                        groups = groups,
                        selectedGroup = selectedGroup,
                        onGroupFocused = { selectedGroup = it },
                        focusRequesters = groupFocusRequesters,
                        modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    )
                    StationList(
                        groupKey = selectedGroup,
                        stations = currentGroupStations,
                        currentStationId = currentStationId,
                        preferredStationId = preferredStationId,
                        favoriteIds = favoriteIds,
                        onSelect = onSelect,
                        onToggleFavorite = onToggleFavorite,
                        modifier = Modifier.weight(0.6f).fillMaxHeight(),
                    )
                }
            }
        }
    }

    LaunchedEffect(groups) {
        try {
            groupFocusRequesters[selectedGroup]?.requestFocus()
        } catch (_: IllegalStateException) {
            // Not laid out yet this frame - list stays usable, just without
            // an initial focus target this one time.
        }
    }
}

@Composable
private fun GroupList(
    groups: List<Pair<String, List<Station>>>,
    selectedGroup: String?,
    onGroupFocused: (String) -> Unit,
    focusRequesters: Map<String, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(groups, key = { _, g -> g.first }) { index, (groupName, groupStations) ->
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val isSelected = groupName == selectedGroup

            val backgroundColor = when {
                isFocused -> Color(0x29FFD166)
                isSelected -> Color(0x2E4E95FF)
                else -> Color.Transparent
            }
            val borderColor = when {
                isFocused -> FocusColor
                isSelected -> AccentColor
                else -> Color.Transparent
            }

            val focusManager = LocalFocusManager.current
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequesters.getValue(groupName))
                    // onFocusChanged must come BEFORE focusable() in the chain -
                    // it only observes focus targets that are its descendants
                    // (later in the chain), so the reverse order silently never
                    // fires. This was the actual bug: focusing a category never
                    // updated the station list on the right.
                    .onFocusChanged { if (it.isFocused) onGroupFocused(groupName) }
                    .then(
                        // Without this, pressing Up on the very first row has no
                        // "up" neighbour in this column, so Compose's spatial
                        // search picks the nearest focusable anywhere on screen -
                        // which is usually the station list on the right. Cancel
                        // makes Up a no-op here instead of jumping across panes.
                        if (index == 0) Modifier.focusProperties { up = FocusRequester.Cancel } else Modifier
                    )
                    .focusable(interactionSource = interactionSource)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
                            // OK on a category also jumps focus straight into
                            // its station list, since some viewers expect OK
                            // to "confirm" rather than relying only on the
                            // focus-follows-preview behaviour above.
                            focusManager.moveFocus(FocusDirection.Right)
                            true
                        } else if (
                            index == 0 &&
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionUp
                        ) {
                            // Belt-and-braces alongside focusProperties above:
                            // on this device, Up on the top row still escaped
                            // to the station list despite up = Cancel, so
                            // swallow the key outright here too.
                            true
                        } else {
                            false
                        }
                    },
                color = backgroundColor,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(2.dp, borderColor),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(groupName, color = TextColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${groupStations.size}", color = MutedColor, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun StationList(
    groupKey: String,
    stations: List<Station>,
    currentStationId: String?,
    preferredStationId: String?,
    favoriteIds: Set<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the category itself (not just its content) so switching
    // categories always gets a fresh scroll position and fresh focus
    // requesters instead of reusing stale ones from the previous list.
    key(groupKey) {
        val listState = rememberLazyListState()
        val itemFocusRequesters = remember(stations) { stations.associate { it.id to FocusRequester() } }
        val targetId = remember(stations, preferredStationId) {
            preferredStationId ?: stations.firstOrNull()?.id
        }
        val targetIndex = remember(stations, targetId) {
            stations.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        }

        LazyColumn(
            state = listState,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(stations, key = { _, s -> s.id }) { index, station ->
                StationListItem(
                    station = station,
                    isCurrent = station.id == currentStationId,
                    isFavorite = station.id in favoriteIds,
                    isFirst = index == 0,
                    onSelect = { onSelect(station.id) },
                    onToggleFavorite = { onToggleFavorite(station.id) },
                    modifier = Modifier.focusRequester(itemFocusRequesters.getValue(station.id)),
                )
            }
        }

        LaunchedEffect(groupKey) {
            // Only keeps the preview scrolled to the right spot when the
            // category changes - it must NOT also grab keyboard focus here.
            // It used to call itemFocusRequesters[targetId]?.requestFocus()
            // too, which fired on every category highlight change (this
            // runs whenever selectedGroup changes, including from just
            // arrowing through the category list on the left) and yanked
            // input focus into this list before the viewer ever pressed
            // Right/OK - that's what made the category list unnavigable
            // ("springt immer rechts rüber"). Entering this list on purpose
            // (OK on a category, or an explicit Right press) already moves
            // focus by itself via GroupList's key handler / the picker's
            // default directional-key handling.
            if (stations.isEmpty()) return@LaunchedEffect
            listState.scrollToItem(targetIndex)
        }
    }
}

@Composable
private fun StationListItem(
    station: Station,
    isCurrent: Boolean,
    isFavorite: Boolean,
    isFirst: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var longPressHandled by remember { mutableStateOf(false) }
    var lastEnterReleaseAt by remember { mutableStateOf(0L) }

    val backgroundColor = when {
        isFocused -> Color(0x29FFD166)
        isCurrent -> Color(0x2E4E95FF)
        else -> Color.Transparent
    }
    val borderColor = when {
        isFocused -> FocusColor
        isCurrent -> AccentColor
        else -> Color.Transparent
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // See the matching comment in GroupList - without this, Up at the
            // top of this column jumps focus over to the category list
            // instead of just staying put.
            .then(if (isFirst) Modifier.focusProperties { up = FocusRequester.Cancel } else Modifier)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.key in ENTER_KEYS) {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            // Long press OK (Android flags this on the
                            // underlying native key event) = toggle it as a
                            // favorite. Acting on it here, as soon as it's
                            // detected, and then suppressing the matching
                            // KeyUp below keeps this from also starting
                            // playback mid-hold - that extra state change was
                            // shifting focus/scroll before the long-press
                            // event landed, so the favorite sometimes ended
                            // up on the wrong (shifted) row.
                            if (event.nativeKeyEvent.isLongPress) {
                                onToggleFavorite()
                                longPressHandled = true
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            // This remote's center button mechanically
                            // overlaps the surrounding d-pad ring on a firm
                            // or held press, occasionally firing a bounced
                            // second OK release a few dozen ms after the
                            // first - ignore any such release so it can't
                            // immediately re-select (and cut off) whatever
                            // the first release just started playing.
                            val now = android.os.SystemClock.uptimeMillis()
                            val isBounce = now - lastEnterReleaseAt < 350
                            lastEnterReleaseAt = now
                            if (!longPressHandled && !isBounce) {
                                onSelect()
                            }
                            longPressHandled = false
                            true
                        }
                        else -> false
                    }
                } else if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown) &&
                    android.os.SystemClock.uptimeMillis() - lastEnterReleaseAt < 350
                ) {
                    // Same mechanical overlap as above, just manifesting as a
                    // ghost Up/Down instead of a second OK - swallow it so a
                    // held OK press can't also shift focus to the
                    // neighbouring row right after selecting.
                    true
                } else if (isFirst && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    // Belt-and-braces alongside focusProperties above: on
                    // this device, Up on the top row has been seen to escape
                    // to the category list despite up = Cancel, so swallow
                    // the key outright here too.
                    true
                } else {
                    false
                }
            },
        color = backgroundColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (if (isFavorite) "★ " else "") + station.name,
                color = if (isFavorite) FocusColor else TextColor,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) {
                Text("▶", color = AccentColor, fontSize = 16.sp)
            }
        }
    }
}
