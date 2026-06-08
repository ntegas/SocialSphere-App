package com.aistudio.socialsphere.crmlxb.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

data class MapLocationItem(
    val id: String,
    val addressId: String,
    val ownerType: AddressOwnerType,
    val ownerId: String,
    val title: String,
    val subtitle: String,
    val addressLine: String,
    val city: String,
    val country: String,
    val locationType: AddressType,
    val latLng: LatLng?,
    val relatedCompanyName: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToContact: (String) -> Unit,
    onNavigateToCompany: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab    by remember { mutableIntStateOf(0) }
    var selectedItem   by remember { mutableStateOf<MapLocationItem?>(null) }
    var showMapView    by remember { mutableStateOf(true) }
    var locationPermGranted by remember { mutableStateOf(false) }
    var mapLoadError by remember { mutableStateOf<String?>(null) }

    // ТЗ: 4 вкладки — Контакты / Рабочие адреса / Компании / Места
    val tabs = listOf("Контакты", "Работа", "Компании", "Места")

    var searchQuery by remember { mutableStateOf("") }

    // Check permission on first composition (don't request automatically)
    LaunchedEffect(Unit) {
        locationPermGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        locationPermGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Build map objects from addresses
    val mapObjects by remember {
        derivedStateOf {
            AppStateStore.addresses.mapNotNull { address ->
                when (address.ownerType) {
                    AddressOwnerType.CONTACT -> {
                        val contact = AppStateStore.getContact(address.ownerId)
                            ?: return@mapNotNull null
                        val compRel = contact.companyRelations.firstOrNull { it.isPrimary }
                            ?: contact.companyRelations.firstOrNull()
                        val companyName = compRel?.companyId
                            ?.let { AppStateStore.getCompany(it)?.name }
                        MapLocationItem(
                            id          = address.id,
                            addressId   = address.id,
                            ownerType   = address.ownerType,
                            ownerId     = address.ownerId,
                            title       = "${contact.firstName} ${contact.lastName}".trim(),
                            subtitle    = listOfNotNull(
                                address.addressType.mapLabel(),
                                companyName,
                                compRel?.position
                            ).joinToString(" · "),
                            addressLine = address.addressLine,
                            city        = address.city,
                            country     = address.country,
                            locationType = address.addressType,
                            latLng      = if (address.latitude != null && address.longitude != null)
                                LatLng(address.latitude, address.longitude) else null,
                            relatedCompanyName = companyName
                        )
                    }
                    AddressOwnerType.COMPANY -> {
                        val company = AppStateStore.getCompany(address.ownerId)
                            ?: return@mapNotNull null
                        MapLocationItem(
                            id          = address.id,
                            addressId   = address.id,
                            ownerType   = address.ownerType,
                            ownerId     = address.ownerId,
                            title       = company.name,
                            subtitle    = "${company.industry.label()} · ${address.addressType.mapLabel()}",
                            addressLine = address.addressLine,
                            city        = address.city,
                            country     = address.country,
                            locationType = address.addressType,
                            latLng      = if (address.latitude != null && address.longitude != null)
                                LatLng(address.latitude, address.longitude) else null,
                            relatedCompanyName = company.name
                        )
                    }
                }
            }
        }
    }

    val filteredList by remember(mapObjects, searchQuery, selectedTab) {
        derivedStateOf {
            mapObjects.filter { obj ->
                val q = "${obj.title} ${obj.subtitle} ${obj.city} ${obj.addressLine}"
                val matchSearch = q.contains(searchQuery, ignoreCase = true)
                val matchTab = when (selectedTab) {
                    0 -> // Контакты — все адреса людей (дом + работа)
                        obj.ownerType == AddressOwnerType.CONTACT
                    1 -> // Рабочие адреса — только рабочие адреса контактов
                        obj.ownerType == AddressOwnerType.CONTACT &&
                        obj.locationType in listOf(AddressType.WORK, AddressType.OFFICE)
                    2 -> // Компании — адреса компаний
                        obj.ownerType == AddressOwnerType.COMPANY
                    3 -> // Места — домашние адреса + прочее
                        obj.locationType in listOf(
                            AddressType.HOME, AddressType.OTHER, AddressType.BRANCH
                        )
                    else -> true
                }
                matchSearch && matchTab
            }
        }
    }

    val geoItems  = filteredList.filter { it.latLng != null }
    val listItems = filteredList

    val defaultLatLng = geoItems.firstOrNull()?.latLng ?: LatLng(37.9838, 23.7275)
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            defaultLatLng,
            if (geoItems.isNotEmpty()) 12f else 5f
        )
    }

    LaunchedEffect(selectedItem) {
        selectedItem?.latLng?.let { latLng ->
            try {
                cameraState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            } catch (e: Exception) {
                // Карта ещё инициализируется — игнорируем
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Карта",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { showMapView = !showMapView }) {
                        Icon(
                            if (showMapView) Icons.Default.FormatListBulleted else Icons.Default.Map,
                            contentDescription = if (showMapView) "Список" else "Карта"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // ── TabRow по ТЗ ─────────────────────────────────────
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = MaterialTheme.colorScheme.background,
                contentColor     = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { idx, title ->
                    val icon = when (idx) {
                        0 -> Icons.Default.Person
                        1 -> Icons.Default.Work
                        2 -> Icons.Default.Business
                        3 -> Icons.Default.Place
                        else -> Icons.Default.LocationOn
                    }
                    Tab(
                        selected  = selectedTab == idx,
                        onClick   = { selectedTab = idx; selectedItem = null },
                        text      = { Text(title, style = MaterialTheme.typography.labelMedium) },
                        icon      = { Icon(icon, null, Modifier.size(16.dp)) }
                    )
                }
            }

            // ── Search ────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Поиск на карте…") },
                leadingIcon  = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty())
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, null)
                        }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            // MAP VIEW
            if (showMapView) {
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    // FIX: show error state instead of crashing
                    if (mapLoadError != null) {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Map,
                                    null,
                                    Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outlineVariant
                                )
                                Text(
                                    "Карта недоступна",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    mapLoadError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                OutlinedButton(onClick = { mapLoadError = null }) {
                                    Text("Попробовать снова")
                                }
                            }
                        }
                    } else {
                        // FIX: safe MapProperties — never pass true if not confirmed
                        val safeLocationEnabled = locationPermGranted && try {
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        } catch (e: Exception) { false }

                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraState,
                            // FIX: guard against SecurityException
                            properties = MapProperties(
                                isMyLocationEnabled = safeLocationEnabled
                            ),
                            uiSettings = MapUiSettings(
                                myLocationButtonEnabled = safeLocationEnabled,
                                zoomControlsEnabled     = true,
                                mapToolbarEnabled       = false
                            ),
                            onMapLoaded = {
                                // Map loaded successfully — clear any error
                                mapLoadError = null
                            }
                        ) {
                            geoItems.forEach { obj ->
                                val hue = when {
                                    obj.ownerType == AddressOwnerType.COMPANY ->
                                        BitmapDescriptorFactory.HUE_AZURE
                                    obj.locationType == AddressType.HOME ->
                                        BitmapDescriptorFactory.HUE_VIOLET
                                    obj.locationType in listOf(
                                        AddressType.WORK, AddressType.OFFICE
                                    ) -> BitmapDescriptorFactory.HUE_GREEN
                                    else -> BitmapDescriptorFactory.HUE_RED
                                }
                                Marker(
                                    state   = MarkerState(position = obj.latLng!!),
                                    title   = obj.title,
                                    snippet = "${obj.subtitle} · ${obj.city}",
                                    icon    = BitmapDescriptorFactory.defaultMarker(hue),
                                    onClick = { selectedItem = obj; false }
                                )
                            }
                        }
                    }

                    // Permission button — only if not granted
                    if (!locationPermGranted) {
                        SmallFloatingActionButton(
                            onClick = {
                                permLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Icon(
                                Icons.Default.MyLocation,
                                "Разрешить геолокацию",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Legend
                    Card(
                        modifier  = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        shape     = RoundedCornerShape(12.dp),
                        colors    = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        ),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MapLegendDot(color = MaterialTheme.colorScheme.primary,   label = "Дом")
                            MapLegendDot(color = MaterialTheme.colorScheme.tertiary,  label = "Работа")
                            MapLegendDot(color = MaterialTheme.colorScheme.secondary, label = "Компания")
                        }
                    }

                    // No-coords notice
                    val noCoordCount = filteredList.count { it.latLng == null }
                    if (noCoordCount > 0) {
                        Card(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            shape    = RoundedCornerShape(10.dp),
                            colors   = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                            )
                        ) {
                            Text(
                                "Без координат: $noCoordCount",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }

            // Bottom list panel
            Card(
                modifier  = Modifier.fillMaxWidth().then(
                    if (showMapView) Modifier.weight(1f) else Modifier.fillMaxHeight()
                ),
                shape     = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedItem != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Выбранный объект",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { selectedItem = null }) {
                                Icon(Icons.Default.Close, null,
                                    tint = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        MapItemDetailCard(
                            item   = selectedItem ?: return@Column,
                            onOpen = {
                                val item = selectedItem ?: return@MapItemDetailCard: return@MapItemDetailCard
                                if (item.ownerType == AddressOwnerType.CONTACT)
                                    onNavigateToContact(item.ownerId)
                                else
                                    onNavigateToCompany(item.ownerId)
                            }
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.LocationOn, null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "В этой области",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    listItems.size.toString(),
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    color    = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(
                            color     = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp
                        )

                        if (listItems.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.SearchOff, null,
                                        Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.outlineVariant)
                                    Text("Адреса не найдены",
                                        color = MaterialTheme.colorScheme.secondary)
                                    Text(
                                        "Добавь адреса в карточки контактов",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(listItems, key = { it.id }) { obj ->
                                    MapListRow(
                                        obj     = obj,
                                        onClick = { selectedItem = obj }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun MapListRow(obj: MapLocationItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val iconRes = when {
        obj.ownerType == AddressOwnerType.COMPANY -> Icons.Default.Business
        obj.locationType == AddressType.HOME      -> Icons.Default.Home
        obj.locationType in listOf(AddressType.WORK, AddressType.OFFICE) -> Icons.Default.Work
        else -> Icons.Default.Person
    }
    val tint = when {
        obj.ownerType == AddressOwnerType.COMPANY -> MaterialTheme.colorScheme.secondary
        obj.locationType == AddressType.HOME      -> MaterialTheme.colorScheme.primary
        obj.locationType in listOf(AddressType.WORK, AddressType.OFFICE) ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconRes, null, Modifier.size(20.dp), tint = tint)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    obj.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(obj.city, obj.subtitle).filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (obj.latLng != null) {
                Box(Modifier.size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary))
            }
            IconButton(
                onClick = {
                    if (obj.latLng != null)
                        ExternalActionHandler.openRouteByCoordinates(
                            ctx, obj.latLng.latitude, obj.latLng.longitude
                        )
                    else
                        ExternalActionHandler.openRoute(
                            ctx, "${obj.addressLine}, ${obj.city}, ${obj.country}"
                        )
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Directions, "Маршрут",
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun MapItemDetailCard(item: MapLocationItem, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(
                        if (item.ownerType == AddressOwnerType.COMPANY)
                            RoundedCornerShape(12.dp) else CircleShape
                    ).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (item.ownerType == AddressOwnerType.COMPANY)
                            Icons.Default.Business else Icons.Default.Person,
                        null, tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    if (item.subtitle.isNotEmpty())
                        Text(item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${item.addressLine}, ${item.city}, ${item.country}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }
            }

            if (item.latLng != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GpsFixed, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                    Text(
                        "${"%.5f".format(item.latLng.latitude)}, " +
                        "${"%.5f".format(item.latLng.longitude)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GpsOff, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant)
                    Text("Нет GPS — маршрут по адресу",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("Открыть")
                }
                OutlinedButton(
                    onClick = {
                        if (item.latLng != null)
                            ExternalActionHandler.openRouteByCoordinates(
                                ctx, item.latLng.latitude, item.latLng.longitude
                            )
                        else
                            ExternalActionHandler.openRoute(
                                ctx, "${item.addressLine}, ${item.city}, ${item.country}"
                            )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Directions, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Маршрут")
                }
            }
        }
    }
}

private fun AddressType.mapLabel(): String = when (this) {
    AddressType.HOME   -> "Дом"
    AddressType.WORK   -> "Работа"
    AddressType.OFFICE -> "Офис"
    AddressType.BRANCH -> "Филиал"
    AddressType.LEGAL  -> "Юр. адрес"
    AddressType.OTHER  -> "Другое"
}
