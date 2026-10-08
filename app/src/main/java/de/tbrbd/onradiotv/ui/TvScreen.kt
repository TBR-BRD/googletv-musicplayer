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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import de.tbrbd.onradiotv.airplay.AirPlayDevice
import de.tbrbd.onradiotv.cast.CastDevice
import de.tbrbd.onradiotv.data.UpnpRenderer
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
    onSetWeatherLocation: (String) -> Unit,
    onRefreshOutputs: () -> Unit,
    onSelectOutput: (String?) -> Unit,
    onAdjustOutputVolume: (Int) -> Unit,
    onExit: () -> Unit,
) {
    var isPickerOpen by remember { mutableStateOf(false) }
    var isLocationDialogOpen by remember { mutableStateOf(false) }
    var isOutputPickerOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Full-bleed: no outer padding and no rounded corners here, so
            // the cover runs flush to the screen's left/top/bottom edges
            // instead of floating as an inset square. Only the text side
            // keeps its own padding. 0.5625 = 9/16 of the width at this
            // screen's 16:9 aspect makes the column exactly as wide as the
            // screen is tall, so a square cover fills it with no letterbox
            // bars under Fit.
            CoverColumn(coverUrl = state.coverUrl, modifier = Modifier.weight(0.5625f).fillMaxHeight())
            SideColumn(
                state = state,
                onOpenPicker = { isPickerOpen = true },
                onOpenLocationDialog = { isLocationDialogOpen = true },
                onOpenOutputPicker = {
                    isOutputPickerOpen = true
                    onRefreshOutputs()
                },
                onExit = onExit,
                // Extra bottom margin, beyond the top/side padding: at least
                // one tested TV crops the picture noticeably more at the
                // bottom edge than elsewhere (overscan not fully compensated
                // in its own firmware), and the button stack grew by a third
                // row - without this the newest (bottom-most) one lands
                // outside the visible/reachable area entirely.
                modifier = Modifier.weight(0.4375f).fillMaxHeight()
                    .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 56.dp),
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

        if (isLocationDialogOpen) {
            WeatherLocationDialog(
                currentLocation = state.weather?.location ?: state.weatherLocationName,
                onConfirm = { name ->
                    onSetWeatherLocation(name)
                    isLocationDialogOpen = false
                },
                onDismiss = { isLocationDialogOpen = false },
            )
        }

        if (isOutputPickerOpen) {
            OutputPickerOverlay(
                renderers = state.upnpRenderers,
                castDevices = state.castDevices,
                airPlayDevices = state.airPlayDevices,
                isDiscovering = state.isDiscoveringUpnp,
                activeOutputRendererId = state.activeOutputRendererId,
                activeOutputVolume = state.activeOutputVolume,
                outputError = state.outputError,
                onRefresh = onRefreshOutputs,
                onSelect = onSelectOutput,
                onAdjustVolume = onAdjustOutputVolume,
                onDismiss = { isOutputPickerOpen = false },
            )
        }

        state.hudText?.let { text ->
            Surface(
                color = PanelColor.copy(alpha = 0.92f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            ) {
                Text(
                    text = text,
                    color = TextColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverColumn(coverUrl: String?, modifier: Modifier = Modifier) {
    // Covers are square but this column is taller than it is wide - Fit
    // (rather than Crop) shows the whole cover with no part cut off,
    // letterboxed against the panel background above/below instead.
    Box(modifier = modifier.background(PanelColor).clipToBounds(), contentAlignment = Alignment.Center) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "Albumcover",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No AsyncImage with a null model renders nothing at all - just
            // an empty panel, easy to mistake for the app having frozen.
            Text(
                text = "🎵",
                fontSize = 120.sp,
                color = MutedColor,
            )
        }
    }
}

@Composable
private fun SideColumn(
    state: TvUiState,
    onOpenPicker: () -> Unit,
    onOpenLocationDialog: () -> Unit,
    onOpenOutputPicker: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentStation = state.stations.find { it.id == state.currentStationId }

    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        // weight(1f) + verticalScroll here are load-bearing, not cosmetic:
        // without them, once the now-playing title wraps to two lines and
        // the weather panel is fully populated, this block's natural height
        // plus the button stack's below can exceed the screen's actual
        // height. A plain Column (no weight on either child) doesn't shrink
        // either child to compensate - Compose was observed collapsing the
        // *second* button (Ausgabe) to a few pixels with no visible content
        // at all. weight(1f) alone (fill=true, the default) bounds this
        // block's height so the button stack below always gets its full
        // natural size, but without verticalScroll the weather panel - which
        // doesn't clip its own overflow - visibly bled past that bound into
        // the buttons instead; scrolling clips cleanly at the boundary.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = currentStation?.name ?: "Radioplayer",
                    color = AccentColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ExitButton(onExit = onExit, modifier = Modifier.padding(start = 12.dp))
            }
            AutoSizeText(
                text = state.nowPlaying?.title ?: "Noch kein Titel",
                color = TextColor,
                maxFontSize = 40.sp,
                minFontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            )
            Text(
                text = state.nowPlaying?.artist ?: "Bitte einen Sender auswählen.",
                color = MutedColor,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )

            WeatherPanel(
                weather = state.weather,
                onOpenLocationDialog = onOpenLocationDialog,
                modifier = Modifier.padding(top = 18.dp),
            )
            // Fixed breathing room before the button stack - without it the
            // buttons sit flush against the weather panel whenever there's
            // no leftover slack in the weighted region above (see the
            // overflow-fix comment on that Column).
            Spacer(modifier = Modifier.height(20.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StationSelectorButton(
                stationName = currentStation?.name ?: "Sender wählen",
                onOpen = onOpenPicker,
            )
            OutputSelectorButton(
                outputName = state.upnpRenderers.find { it.id == state.activeOutputRendererId }?.friendlyName
                    ?: state.castDevices.find { "cast:${it.routeId}" == state.activeOutputRendererId }?.name
                    ?: state.airPlayDevices.find { "airplay:${it.routeId}" == state.activeOutputRendererId }?.name
                    ?: "Dieser Fernseher",
                onOpen = onOpenOutputPicker,
            )
        }
    }
}

/** Fully kills the app process (not just finish()) - the TV launcher's own
 * long-press context menu on this device only offers "Verschieben/Öffnen/
 * Deinstallieren", no force-stop, so this is the only in-app way to get a
 * genuinely fresh process (clearing CastV2Client sockets, discovery state,
 * etc.) without going through Settings -> Apps -> App-Infos. Placed as a
 * small top-right corner button rather than a third row in the Sender/
 * Ausgabe stack - that stack already sits close to the bottom edge on at
 * least one tested TV (overscan not fully compensated in its own
 * firmware), where a third row risked landing outside the visible/
 * reachable area entirely. */
@Composable
private fun ExitButton(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor = if (isFocused) FocusColor else Color(0x1AFFFFFF)
    val backgroundColor = if (isFocused) Color(0x29FFD166) else PanelColor

    Surface(
        modifier = modifier
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
                    onExit()
                    true
                } else {
                    false
                }
            },
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Text(
            "Beenden",
            color = MutedColor,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A single-line Text that shrinks its own font size (in 2sp steps) until it
 * fits the available width, instead of wrapping - used for the now-playing
 * title so a long one never grows the surrounding layout (which pushed the
 * Sender/Ausgabe buttons down/off in exactly this situation - see the
 * overflow-fix comment on SideColumn's top block). Stays invisible for the
 * handful of measurement passes it takes to settle, to avoid a visible flash
 * at the wrong size. */
@Composable
private fun AutoSizeText(
    text: String,
    color: Color,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > minFontSize) {
                fontSize = androidx.compose.ui.unit.TextUnit(
                    (fontSize.value - 2f).coerceAtLeast(minFontSize.value),
                    androidx.compose.ui.unit.TextUnitType.Sp,
                )
            } else {
                readyToDraw = true
            }
        },
    )
}

// Mirrors app/weather_service.py's icon_slug -> SVG mapping; this app has no
// server to serve those SVGs from, so an emoji per slug stands in instead.
private val WEATHER_ICON_EMOJI: Map<String, String> = mapOf(
    "sunny" to "☀️",
    "mostly-clear" to "🌤️",
    "partly-cloudy" to "⛅",
    "cloudy" to "☁️",
    // "🌫️" (fog, Unicode 8.0) renders as a missing-glyph box on at least one
    // tested TV's emoji font - "🌁" (foggy, Unicode 6.0) is much older and
    // far more broadly supported.
    "fog" to "🌁",
    "drizzle" to "🌦️",
    "rain" to "🌧️",
    "freezing" to "🥶",
    "snow" to "❄️",
    "thunder" to "⛈️",
)

private val PRESSURE_TREND_SYMBOL: Map<String, String> = mapOf(
    "up" to "↑",
    "down" to "↓",
    "steady" to "→",
)

@Composable
private fun WeatherPanel(
    weather: WeatherState?,
    onOpenLocationDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (weather == null) return

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
    val dateFormat = remember { SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY) }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
                    onOpenLocationDialog()
                    true
                } else {
                    false
                }
            },
        color = PanelColor,
        shape = RoundedCornerShape(16.dp),
        border = if (isFocused) BorderStroke(2.dp, FocusColor) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(text = weather.location, color = MutedColor, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Text(
                            text = weather.pressureHpa?.let { "Luftdruck: $it hPa" } ?: "Luftdruck: -",
                            color = MutedColor,
                            fontSize = 14.sp,
                        )
                        weather.pressureTrend?.let { trend ->
                            Text(
                                text = "  ${PRESSURE_TREND_SYMBOL[trend] ?: ""}",
                                color = MutedColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = timeFormat.format(now), color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = dateFormat.format(now), color = MutedColor, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    text = weather.iconSlug?.let { WEATHER_ICON_EMOJI[it] } ?: "",
                    fontSize = 30.sp,
                )
                Text(
                    text = weather.temperatureC?.let { "${it.toInt()}°" } ?: "--°",
                    color = TextColor,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
                Text(
                    text = "  ${weather.condition}",
                    color = MutedColor,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            ) {
                weather.days.take(3).forEach { day ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = day.label, color = MutedColor, fontSize = 14.sp)
                        Text(
                            text = day.iconSlug?.let { WEATHER_ICON_EMOJI[it] } ?: "",
                            fontSize = 22.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        val max = day.tempMaxC?.let { "${it.toInt()}°" } ?: "--°"
                        val min = day.tempMinC?.let { "${it.toInt()}°" } ?: "--°"
                        Text(
                            text = "$max / $min",
                            color = TextColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The always-visible trigger on the main screen; opens the full picker. */
@Composable
private fun StationSelectorButton(stationName: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }

    SelectorButton(label = "Sender", value = stationName, onOpen = onOpen, modifier = modifier.focusRequester(focusRequester))

    // The main screen's first focusable widget, so it should already carry
    // the D-pad focus as soon as the screen appears.
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // Not laid out yet on this frame - harmless, the button is still
            // reachable, it just won't have focus by default this one time.
        }
    }
}

/** Opens the WLAN-speaker (UPnP/Sonos) picker; sits next to the station
 * selector on the main screen. */
@Composable
private fun OutputSelectorButton(outputName: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    SelectorButton(label = "Ausgabe", value = outputName, onOpen = onOpen, modifier = modifier)
}

@Composable
private fun SelectorButton(label: String, value: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor = if (isFocused) FocusColor else Color(0x1AFFFFFF)
    val backgroundColor = if (isFocused) Color(0x29FFD166) else PanelColor

    Surface(
        modifier = modifier
            .fillMaxWidth()
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(label, color = MutedColor, fontSize = 11.sp)
            Text(
                value,
                color = TextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val currentGroup = remember(stations, currentStationId, favoriteIds) {
        when {
            // If the current pick is a favorite, land back on ★ Favoriten
            // rather than its own genre category - picking a station via
            // the favorites list and reopening the picker right after
            // should return there, not bounce you into wherever that
            // station natively lives.
            currentStationId in favoriteIds -> FAVORITES_GROUP
            else -> stations.find { it.id == currentStationId }?.group ?: groups.firstOrNull()?.first ?: ""
        }
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
                    .then(
                        // Same idea at the bottom: without this, Down on the
                        // last category has no neighbour below and can escape
                        // the whole picker to whatever's behind it.
                        if (index == groups.size - 1) Modifier.focusProperties { down = FocusRequester.Cancel } else Modifier
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
                            event.type == KeyEventType.KeyDown &&
                            ((index == 0 && event.key == Key.DirectionUp) ||
                                (index == groups.size - 1 && event.key == Key.DirectionDown))
                        ) {
                            // Belt-and-braces alongside focusProperties above:
                            // on this device, Up/Down at a list boundary still
                            // escaped the picker despite up/down = Cancel, so
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
                    isLast = index == stations.size - 1,
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
    isLast: Boolean,
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
            // Same idea at the bottom: without this, Down on the last
            // station has no neighbour below and can escape the whole
            // picker to whatever's behind it.
            .then(if (isLast) Modifier.focusProperties { down = FocusRequester.Cancel } else Modifier)
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
                } else if (isLast && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    // Same belt-and-braces at the bottom boundary.
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

/** Opened by pressing OK on the weather panel; lets the viewer change the
 * city the forecast is for without needing adb/a settings file. */
@Composable
private fun WeatherLocationDialog(
    currentLocation: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentLocation) }
    val fieldFocusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = PanelColor,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.width(440.dp).padding(24.dp)) {
                Text(
                    text = "Ort für die Wettervorhersage",
                    color = TextColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .focusRequester(fieldFocusRequester),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x1AFFFFFF),
                        unfocusedContainerColor = Color(0x1AFFFFFF),
                        focusedTextColor = TextColor,
                        unfocusedTextColor = TextColor,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogButton(label = "Abbrechen", onClick = onDismiss)
                    DialogButton(
                        label = "Speichern",
                        onClick = { onConfirm(text) },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            fieldFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // Not laid out yet this frame - the field is still reachable by
            // D-pad, it just won't be pre-focused this one time.
        }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = modifier
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
                    onClick()
                    true
                } else {
                    false
                }
            },
        color = if (isFocused) Color(0x29FFD166) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, if (isFocused) FocusColor else Color(0x1AFFFFFF)),
    ) {
        Text(
            text = label,
            color = TextColor,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Lets the viewer pick between this TV's own speaker and any UPnP/Sonos
 * renderer found on the LAN, and adjust the active renderer's volume - the
 * same local-network discovery and AVTransport/RenderingControl calls
 * app/upnp_renderer.py uses on the Pi, just triggered from this UI instead
 * of a web page. */
@Composable
private fun OutputPickerOverlay(
    renderers: List<UpnpRenderer>,
    castDevices: List<CastDevice>,
    airPlayDevices: List<AirPlayDevice>,
    isDiscovering: Boolean,
    activeOutputRendererId: String?,
    activeOutputVolume: Int?,
    outputError: String?,
    onRefresh: () -> Unit,
    onSelect: (String?) -> Unit,
    onAdjustVolume: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val focusManager = LocalFocusManager.current
    val firstRowFocusRequester = remember { FocusRequester() }

    // Without this, opening the overlay never actually moves Android's real
    // input focus into it (it just gets drawn on top) - D-pad events kept
    // going to whatever was focused before (the "Ausgabe" button behind
    // it), so Up/Down inside the overlay did nothing.
    LaunchedEffect(Unit) {
        try {
            firstRowFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // Not laid out yet this frame - the list stays usable, it just
            // won't have a specific initial focus target this one time.
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScrimColor)
            // Self-healing: if focus ever ends up outside this overlay's
            // subtree entirely (observed live - refreshing the device list
            // while a row was focused could knock focus back out to the
            // "Ausgabe" button hidden behind this overlay), immediately
            // reclaim it instead of leaving the D-pad silently controlling
            // whatever's behind the scrim.
            .onFocusChanged { state ->
                if (!state.hasFocus) {
                    try {
                        firstRowFocusRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                        // Not laid out yet - will self-heal on the next
                        // focus-state change instead.
                    }
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        focusManager.moveFocus(FocusDirection.Up); true
                    }
                    Key.DirectionDown -> {
                        focusManager.moveFocus(FocusDirection.Down); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight(0.7f)
                .width(620.dp)
                .padding(end = 24.dp),
            color = PanelColor,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Ausgabe wählen", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "OK wählt · bei WLAN-Lautsprechern ◀ ▶ = Lautstärke · Zurück schließt",
                    color = MutedColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )

                if (outputError != null) {
                    Text(
                        text = "Fehler: $outputError",
                        color = Color(0xFFFF8A8A),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OutputRow(
                            name = "Dieser Fernseher",
                            isActive = activeOutputRendererId == null,
                            volume = null,
                            onSelect = { onSelect(null) },
                            onAdjustVolume = null,
                            modifier = Modifier.focusRequester(firstRowFocusRequester),
                        )
                    }
                    itemsIndexed(renderers, key = { _, r -> r.id }) { _, renderer ->
                        OutputRow(
                            name = "${renderer.friendlyName} (UPnP)",
                            isActive = activeOutputRendererId == renderer.id,
                            volume = if (activeOutputRendererId == renderer.id) activeOutputVolume else null,
                            onSelect = { onSelect(renderer.id) },
                            onAdjustVolume = if (activeOutputRendererId == renderer.id) onAdjustVolume else null,
                        )
                    }
                    itemsIndexed(castDevices, key = { _, d -> d.routeId }) { _, device ->
                        // Must match TvViewModel's CAST_PREFIX ("cast:") -
                        // that's how it tells a Cast route apart from a UPnP
                        // renderer id sharing the same activeOutputRendererId
                        // field.
                        val castOutputId = "cast:${device.routeId}"
                        OutputRow(
                            name = "${device.name} (Google Cast)",
                            isActive = activeOutputRendererId == castOutputId,
                            volume = if (activeOutputRendererId == castOutputId) activeOutputVolume else null,
                            onSelect = { onSelect(castOutputId) },
                            onAdjustVolume = if (activeOutputRendererId == castOutputId) onAdjustVolume else null,
                        )
                    }
                    itemsIndexed(airPlayDevices, key = { _, d -> d.routeId }) { _, device ->
                        // Must match TvViewModel's AIRPLAY_PREFIX ("airplay:").
                        val airPlayOutputId = "airplay:${device.routeId}"
                        OutputRow(
                            name = "${device.name} (AirPlay)",
                            isActive = activeOutputRendererId == airPlayOutputId,
                            volume = if (activeOutputRendererId == airPlayOutputId) activeOutputVolume else null,
                            onSelect = { onSelect(airPlayOutputId) },
                            onAdjustVolume = if (activeOutputRendererId == airPlayOutputId) onAdjustVolume else null,
                        )
                    }
                }

                RefreshRow(
                    isDiscovering = isDiscovering,
                    onRefresh = onRefresh,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun RefreshRow(isDiscovering: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // This is the last focusable row in the picker - without this,
            // pressing Down here has no further sibling within the overlay,
            // so Compose's spatial search looks outside it and lands on
            // whatever's behind the scrim (same class of bug as the station
            // picker's top-row escape, fixed the same way: swallow the key
            // outright rather than relying on focusProperties alone).
            .focusProperties { down = FocusRequester.Cancel }
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    in ENTER_KEYS -> {
                        onRefresh(); true
                    }
                    Key.DirectionDown -> true
                    else -> false
                }
            },
        color = if (isFocused) Color(0x29FFD166) else PanelColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, if (isFocused) FocusColor else Color(0x1AFFFFFF)),
    ) {
        Text(
            text = if (isDiscovering) "Suche läuft …" else "Suche nach WLAN-Lautsprechern aktualisieren",
            color = if (isDiscovering) MutedColor else TextColor,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun OutputRow(
    name: String,
    isActive: Boolean,
    volume: Int?,
    onSelect: () -> Unit,
    onAdjustVolume: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val backgroundColor = when {
        isFocused -> Color(0x29FFD166)
        isActive -> Color(0x2E4E95FF)
        else -> Color.Transparent
    }
    val borderColor = when {
        isFocused -> FocusColor
        isActive -> AccentColor
        else -> Color.Transparent
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    event.key in ENTER_KEYS -> {
                        onSelect(); true
                    }
                    onAdjustVolume != null && event.key == Key.DirectionRight -> {
                        onAdjustVolume(5); true
                    }
                    onAdjustVolume != null && event.key == Key.DirectionLeft -> {
                        onAdjustVolume(-5); true
                    }
                    else -> false
                }
            },
        color = backgroundColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                color = if (isActive) FocusColor else TextColor,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Text("▶", color = AccentColor, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
            }
            if (volume != null) {
                Text("◀ $volume% ▶", color = MutedColor, fontSize = 14.sp)
            }
        }
    }
}
