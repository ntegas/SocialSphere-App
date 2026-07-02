package com.aistudio.socialsphere.crmlxb.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import androidx.core.content.ContextCompat
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*
import com.google.android.gms.maps.CameraUpdateFactory
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
    var mapTilesLoaded by remember { mutableStateOf(false) }

    // Диагностика «синего экрана»: если тайлы не пришли за 8 сек —
    // почти наверняка проблема API-ключа (Maps SDK for Android не включён
    // в Google Cloud Console) или нет интернета
    // stringResource нельзя звать внутри LaunchedEffect (корутина, не @Composable) —
    // захватываем текст в composable-скоупе, в эффекте только присваиваем.
    val mapLoadErrorText = stringResource(R.string.map_load_failed_1) +
        stringResource(R.string.map_load_failed_2) +
        stringResource(R.string.map_load_failed_3)
    LaunchedEffect(showMapView) {
        if (!showMapView) return@LaunchedEffect
        kotlinx.coroutines.delay(8000)
        if (!mapTilesLoaded && mapLoadError == null) {
            mapLoadError = mapLoadErrorText
        }
    }

    // Обновлённое ТЗ: 3 вкладки, «Места» удалена
    val tabs = listOf(stringResource(R.string.map_tab_contacts), stringResource(R.string.map_tab_work), stringResource(R.string.map_tab_companies))

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

    // stringResource нельзя в derivedStateOf — захватываем лейблы типов адреса
    // здесь и передаём в mapLabel(); они же ключ remember для смены языка
    val addrLabels = mapOf(
        AddressType.HOME   to stringResource(R.string.addr_home),
        AddressType.WORK   to stringResource(R.string.addr_work),
        AddressType.OFFICE to stringResource(R.string.addr_office),
        AddressType.BRANCH to stringResource(R.string.addr_branch),
        AddressType.LEGAL  to stringResource(R.string.addr_legal),
        AddressType.OTHER  to stringResource(R.string.common_other)
    )
    // Industry-лейблы захватываем здесь (derivedStateOf ниже не может звать stringResource)
    val industryCtx = androidx.compose.ui.platform.LocalContext.current
    val industryLabels = Industry.entries.associateWith { it.label(industryCtx) }
    // Build map objects from addresses
    val mapObjects by remember(addrLabels, industryLabels) {
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
                                address.addressType.mapLabel(addrLabels),
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
                            subtitle    = "${industryLabels[company.industry] ?: ""} · ${address.addressType.mapLabel(addrLabels)}",
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

    // Сессионный кэш геокодинга: адреса без координат получают точку на карте
    val geoCache = remember { mutableStateMapOf<String, LatLng>() }
    val coordsOf: (MapLocationItem) -> LatLng? = { it.latLng ?: geoCache[it.addressId] }

    val filteredList by remember(mapObjects, searchQuery, selectedTab) {
        derivedStateOf {
            mapObjects.filter { obj ->
                val q = "${obj.title} ${obj.subtitle} ${obj.city} ${obj.addressLine}"
                val matchSearch = q.contains(searchQuery, ignoreCase = true)
                val matchTab = when (selectedTab) {
                    0 -> // Контакты — ТОЛЬКО личные адреса людей (рабочие — на вкладке «Работа»)
                        obj.ownerType == AddressOwnerType.CONTACT &&
                        obj.locationType !in listOf(AddressType.WORK, AddressType.OFFICE)
                    1 -> // Рабочие адреса — только рабочие адреса контактов
                        obj.ownerType == AddressOwnerType.CONTACT &&
                        obj.locationType in listOf(AddressType.WORK, AddressType.OFFICE)
                    2 -> // Компании — адреса компаний
                        obj.ownerType == AddressOwnerType.COMPANY
                    else -> true
                }
                matchSearch && matchTab
            }
        }
    }

    val geoItems by remember {
        derivedStateOf { filteredList.filter { coordsOf(it) != null } }
    }

    // Фоновый геокодинг адресов без координат (Android Geocoder, без API-ключа).
    // Берём ВСЕ адреса без координат (раньше только 15 → дальние точки пропадали
    // и появлялись лишь по тапу). Результат пишем в БД (setAddressCoords) — точка
    // больше не теряется и карта не геокодит заново при следующих открытиях.
    LaunchedEffect(filteredList) {
        val pending = filteredList
            .filter { it.latLng == null && !geoCache.containsKey(it.addressId) }
            .take(200)
        if (pending.isEmpty()) return@LaunchedEffect
        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
            pending.mapNotNull { obj ->
                try {
                    val query = listOf(obj.addressLine, obj.city, obj.country)
                        .filter { it.isNotBlank() }.joinToString(", ")
                    if (query.isBlank()) return@mapNotNull null
                    @Suppress("DEPRECATION")
                    val hit = geocoder.getFromLocationName(query, 1)?.firstOrNull()
                        ?: return@mapNotNull null
                    obj.addressId to LatLng(hit.latitude, hit.longitude)
                } catch (e: Exception) { null }
            }
        }
        geoCache.putAll(resolved)
        // Персистим координаты в БД, чтобы не геокодить заново и точки не пропадали
        resolved.forEach { (id, ll) -> AppStateStore.setAddressCoords(id, ll.latitude, ll.longitude) }
    }

    // Геокодим выбранный контакт по требованию: если у его адреса нет координат
    // (и фоновый геокодер до него не дошёл) — находим их сразу, чтобы маркер
    // появился и карта на нём сцентрировалась.
    LaunchedEffect(selectedItem) {
        val sel = selectedItem ?: return@LaunchedEffect
        if (sel.latLng != null || geoCache.containsKey(sel.addressId)) return@LaunchedEffect
        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!android.location.Geocoder.isPresent()) return@withContext null
            try {
                val query = listOf(sel.addressLine, sel.city, sel.country)
                    .filter { it.isNotBlank() }.joinToString(", ")
                if (query.isBlank()) return@withContext null
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, java.util.Locale.getDefault())
                    .getFromLocationName(query, 1)?.firstOrNull()
                    ?.let { LatLng(it.latitude, it.longitude) }
            } catch (e: Exception) { null }
        }
        if (resolved != null) {
            geoCache[sel.addressId] = resolved
            AppStateStore.setAddressCoords(sel.addressId, resolved.latitude, resolved.longitude)
        }
    }
    val listItems = filteredList

    // Местоположение пользователя (last-known) — чтобы карта открывалась там,
    // где он сейчас, а не на первом адресе из данных (был в другом городе).
    var userLatLng by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(locationPermGranted) {
        if (!locationPermGranted) return@LaunchedEffect
        userLatLng = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            lastKnownLatLng(context)
        }
    }

    val defaultLatLng = userLatLng ?: geoItems.firstOrNull()?.latLng ?: LatLng(37.9838, 23.7275)
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            defaultLatLng,
            if (geoItems.isNotEmpty()) 12f else 5f
        )
    }

    // Когда местоположение получено и пользователь ничего не выбрал — центрируем
    // карту на нём (камера инициализируется один раз, поэтому двигаем эффектом).
    LaunchedEffect(userLatLng) {
        val u = userLatLng ?: return@LaunchedEffect
        if (selectedItem == null) {
            try {
                cameraState.animate(CameraUpdateFactory.newLatLngZoom(u, 12f))
            } catch (e: Exception) { /* карта ещё инициализируется */ }
        }
    }

    LaunchedEffect(selectedItem, geoCache.size, showMapView) {
        selectedItem?.let { coordsOf(it) }?.let { latLng ->
            try {
                cameraState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            } catch (e: Exception) {
                // Карта ещё инициализируется — игнорируем
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // -- Base layer: full-screen map --
            if (showMapView) {
                Box(modifier = Modifier.fillMaxSize()) {

                    // FIX: show error state instead of crashing
                    if (mapLoadError != null) {
                        Box(
                            Modifier.fillMaxSize().background(AppleTheme.colors.card),
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
                                    tint = AppleTheme.colors.separator
                                )
                                Text(
                                    stringResource(R.string.map_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppleTheme.colors.secondaryLabel
                                )
                                Text(
                                    mapLoadError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppleTheme.colors.secondaryLabel
                                )
                                OutlinedButton(onClick = { mapLoadError = null }) {
                                    Text(stringResource(R.string.map_retry))
                                }
                            }
                        }
                    } else {
                        // ТЗ: обязательная проверка GMS перед GoogleMap
                        val isGmsAvailable = remember(context) {
                            try {
                                com.google.android.gms.common.GoogleApiAvailability
                                    .getInstance()
                                    .isGooglePlayServicesAvailable(context) ==
                                    com.google.android.gms.common.ConnectionResult.SUCCESS
                            } catch (e: Exception) { false }
                        }

                        if (!isGmsAvailable) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Map, null,
                                        Modifier.size(48.dp),
                                        tint = AppleTheme.colors.separator)
                                    Text(stringResource(R.string.map_unavailable),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(R.string.map_gms_update),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppleTheme.colors.secondaryLabel
                                    )
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
                                mapTilesLoaded = true
                                mapLoadError = null
                            }
                        ) {
                            geoItems.forEach { obj ->
                                val latLng = coordsOf(obj) ?: return@forEach
                                val isSelected = obj.id == selectedItem?.id
                                // Маркер — круглый аватар-пин (как в макете Aurelia): контакт —
                                // градиент+инициалы (тот же стиль, что в MapItemDetailCard ниже),
                                // компания — плитка с иконкой на брендовом фоне.
                                MarkerComposable(
                                    keys    = arrayOf(obj.id, isSelected),
                                    state   = MarkerState(position = latLng),
                                    title   = obj.title,
                                    snippet = "${obj.subtitle} · ${obj.city}",
                                    zIndex  = if (isSelected) 1f else 0f,
                                    onClick = { selectedItem = obj; false }
                                ) {
                                    MapMarkerPin(obj = obj, selected = isSelected)
                                }
                            }
                        }
                    }
                    } // end isGmsAvailable

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
                            containerColor = AppleTheme.colors.card
                        ) {
                            Icon(
                                Icons.Default.MyLocation,
                                stringResource(R.string.map_grant_location),
                                tint = AppleTheme.colors.brand
                            )
                        }
                    }

                    // No-coords notice
                    val noCoordCount = filteredList.count { it.latLng == null }
                    if (noCoordCount > 0) {
                        Card(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            shape    = RoundedCornerShape(10.dp),
                            colors   = CardDefaults.cardColors(
                                containerColor = AppleTheme.colors.card.copy(alpha = 0.92f)
                            )
                        ) {
                            Text(
                                stringResource(R.string.map_no_coords, noCoordCount),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = AppleTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                
                }
            }

            // -- Bottom panel: selected (floating) or full list --
            if (!showMapView || selectedItem != null) {
                val panelMod = if (showMapView)
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)
                else
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.82f)
                Card(
                    modifier  = panelMod,
                    shape     = if (showMapView) RoundedCornerShape(20.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (showMapView) 12.dp else 8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(AppleTheme.colors.separator))
                        }
                        if (selectedItem != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.map_selected_object), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
                                IconButton(onClick = { selectedItem = null }) { Icon(Icons.Default.Close, null, tint = AppleTheme.colors.secondaryLabel) }
                            }
                            MapItemDetailCard(
                                item   = selectedItem ?: return@Column,
                                onOpen = {
                                    val item = selectedItem ?: return@MapItemDetailCard
                                    if (item.ownerType == AddressOwnerType.CONTACT) onNavigateToContact(item.ownerId)
                                    else onNavigateToCompany(item.ownerId)
                                }
                            )
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.LocationOn, null, tint = AppleTheme.colors.brand)
                                    Text(stringResource(R.string.map_in_this_area), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                Badge(containerColor = AppleTheme.colors.brand.copy(alpha = 0.10f)) {
                                    Text(listItems.size.toString(), modifier = Modifier.padding(horizontal = 4.dp), color = AppleTheme.colors.brand, fontWeight = FontWeight.Bold)
                                }
                            }
                            HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                            if (listItems.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = AppleTheme.colors.separator)
                                        Text(stringResource(R.string.map_no_addresses), color = AppleTheme.colors.secondaryLabel)
                                        Text(stringResource(R.string.map_add_addresses_hint), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    items(listItems, key = { it.id }) { obj ->
                                        MapListRow(obj = obj, onClick = { selectedItem = obj; showMapView = true })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // -- Floating search + chips over the map (HTML spec) --
            Column(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).height(48.dp),
                        placeholder = { Text(stringResource(R.string.map_search_hint), color = AppleTheme.colors.secondaryLabel) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = AppleTheme.colors.secondaryLabel, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppleTheme.colors.card,
                            unfocusedContainerColor = AppleTheme.colors.card,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(AppleTheme.colors.brand).clickable { showMapView = !showMapView },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (showMapView) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Default.Map,
                            contentDescription = if (showMapView) stringResource(R.string.map_list) else stringResource(R.string.map_title),
                            tint = Color.White, modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEachIndexed { idx, title ->
                        val active = selectedTab == idx
                        Box(
                            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (active) AppleTheme.colors.brand else AppleTheme.colors.card)
                                .clickable { selectedTab = idx; selectedItem = null }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, color = if (active) Color.White else AppleTheme.colors.label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Пин на карте в стиле Aurelia: контакт — круглый градиент-аватар с инициалами
 * (тот же токен [AureliaTheme.colors.avatarTerracotta], что и в [MapItemDetailCard]),
 * компания — плитка с иконкой на брендовом фоне. Выбранный элемент — крупнее,
 * с золотой окантовкой вместо обычной card-рамки.
 */
@Composable
private fun MapMarkerPin(obj: MapLocationItem, selected: Boolean) {
    val size = if (selected) 52.dp else 44.dp
    val ringColor = if (selected) AureliaTheme.colors.gold else AppleTheme.colors.card
    val ringWidth = if (selected) 3.dp else 2.dp
    if (obj.ownerType == AddressOwnerType.COMPANY) {
        Box(
            modifier = Modifier.size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(AppleTheme.colors.brand)
                .border(ringWidth, ringColor, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Business, null, tint = Color.White, modifier = Modifier.size(size / 2))
        }
    } else {
        val initials = obj.title.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2).joinToString("")
        Box(
            modifier = Modifier.size(size)
                .clip(CircleShape)
                .background(AureliaTheme.colors.avatarTerracotta)
                .border(ringWidth, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.8).sp)
        }
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
    // Палитра Aurelia (совпадает с легендой и маркерами): дом — малахит,
    // работа — золото, компания — терракот.
    val tint = when {
        obj.ownerType == AddressOwnerType.COMPANY -> AppleTheme.colors.red
        obj.locationType == AddressType.HOME      -> AppleTheme.colors.brand
        obj.locationType in listOf(AddressType.WORK, AddressType.OFFICE) ->
            AppleTheme.colors.orange
        else -> AppleTheme.colors.brand
    }

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(
            containerColor = AppleTheme.colors.card.copy(alpha = 0.4f)
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
                    color = AppleTheme.colors.secondaryLabel,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (obj.latLng != null) {
                Box(Modifier.size(8.dp).clip(CircleShape)
                    .background(AppleTheme.colors.orange))
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
                Icon(Icons.Default.Directions, stringResource(R.string.map_route),
                    Modifier.size(20.dp), tint = AppleTheme.colors.brand)
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
            containerColor = AppleTheme.colors.card.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (item.ownerType == AddressOwnerType.COMPANY) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Business, null, tint = AppleTheme.colors.brand)
                    }
                } else {
                    // Аватар-пин в стиле Aurelia: терракотовый градиент + инициалы
                    val initials = item.title.split(" ")
                        .mapNotNull { it.firstOrNull()?.toString() }
                        .take(2).joinToString("")
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(AureliaTheme.colors.avatarTerracotta),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials, color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    if (item.subtitle.isNotEmpty())
                        Text(item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.secondaryLabel)
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
                        tint = AppleTheme.colors.orange)
                    Text(
                        "${"%.5f".format(item.latLng.latitude)}, " +
                        "${"%.5f".format(item.latLng.longitude)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.orange
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GpsOff, null, Modifier.size(14.dp),
                        tint = AppleTheme.colors.separator)
                    Text(stringResource(R.string.map_no_gps_route),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.map_open))
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
                    Text(stringResource(R.string.map_route))
                }
            }
        }
    }
}

private fun AddressType.mapLabel(labels: Map<AddressType, String>): String =
    labels[this] ?: this.name

/**
 * Последняя известная позиция устройства через системный LocationManager
 * (без play-services-location). Вызывается только при выданном разрешении.
 * Берём самый точный из доступных провайдеров. null — если ничего нет.
 */
private fun lastKnownLatLng(context: android.content.Context): LatLng? {
    return try {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null
        var best: android.location.Location? = null
        for (provider in lm.getProviders(true)) {
            @Suppress("MissingPermission")
            val loc = try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
                ?: continue
            val current = best
            if (current == null || loc.accuracy < current.accuracy) best = loc
        }
        best?.let { LatLng(it.latitude, it.longitude) }
    } catch (e: Exception) {
        null
    }
}
