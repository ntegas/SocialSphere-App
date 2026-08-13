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
    val relatedCompanyName: String? = null,
    val relatedCompanyId: String? = null,
    val relatedPosition: String? = null
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
    // Фильтр по типу отношений (фидбэк владельца: «показывать только семью/друзей/
    // коллег» — до появления полноценных групп). null = все.
    var relFilter      by remember { mutableStateOf<RelationshipType?>(null) }
    // Свой тип отношений («статус») — раньше вообще не был виден как фильтр на
    // карте (relationshipType таких контактов = OTHER, не входит в relFilter).
    // Фидбэк владельца 2026-07-05: «создаю статус сам — не входит в фильтры».
    var customRelFilter by remember { mutableStateOf<String?>(null) }
    // Фильтр по группе (v11): совместим с типом отношений — оба условия «И»
    var groupFilter    by remember { mutableStateOf<String?>(null) }
    // Фильтр по компании/должности — только для вкладки «Работа» (фидбэк владельца
    // 2026-07-04: «в работах в карте должен быть фильтр позиции компании»)
    var companyFilter  by remember { mutableStateOf<String?>(null) }
    var positionFilter by remember { mutableStateOf<String?>(null) }
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

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        locationPermGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Check permission on first composition, and PROACTIVELY request it if not
    // yet granted — раньше только проверяли и молча ждали, пока владелец сам
    // не нажмёт кнопку «где я» (которая к тому же в углу, легко не заметить).
    // ФИКС (аудит 2026-08-11, жалоба владельца: «при первом включении карты сразу
    // должен спросить про GPS, иначе люди не увидят себя»): запрашиваем сразу при
    // первом открытии вкладки, если разрешения ещё нет. Системный диалог сам
    // перестаёт появляться после повторных отказов (стандартное поведение
    // Android) — это не превращается в назойливый повтор на каждый заход.
    // FIX (корень бага «пропала точка/кнопка, острова», 2026-07-04): проверяем
    // ОБА разрешения — FINE и COARSE. Раньше здесь был только FINE, а permLauncher
    // выше засчитывает и COARSE. Из-за этого при выданном «Приблизительно» карта
    // работала в ту сессию, но при следующем запуске эта проверка видела «точного
    // нет» → locationPermGranted=false → нет синей точки, нет кнопки «где я»,
    // userLatLng не запрашивался → карта падала на заглушку (Афины, острова).
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        locationPermGranted = granted
        if (!granted) {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
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
                            relatedCompanyName = companyName,
                            relatedCompanyId = compRel?.companyId,
                            relatedPosition = compRel?.position
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
                            relatedCompanyName = company.name,
                            relatedCompanyId = company.id
                        )
                    }
                }
            }
        }
    }

    // Сессионный кэш геокодинга: адреса без координат получают точку на карте
    val geoCache = remember { mutableStateMapOf<String, LatLng>() }
    val coordsOf: (MapLocationItem) -> LatLng? = { it.latLng ?: geoCache[it.addressId] }

    val filteredList by remember(mapObjects, searchQuery, selectedTab, relFilter, customRelFilter, groupFilter, companyFilter, positionFilter) {
        derivedStateOf {
            val groupContactIds = groupFilter?.let { AppStateStore.contactIdsInGroup(it) }
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
                // Фильтр отношений: активен → только контакты выбранного типа
                // (адреса компаний при активном фильтре скрываются — у них нет типа отношений)
                val matchRel = (relFilter == null && customRelFilter == null) ||
                    (obj.ownerType == AddressOwnerType.CONTACT &&
                        AppStateStore.getContact(obj.ownerId).let { c ->
                            (relFilter != null && c?.relationshipType == relFilter) ||
                            (customRelFilter != null && c?.customRelationshipType == customRelFilter)
                        })
                // Фильтр группы: только контакты-члены выбранной группы
                val matchGroup = groupContactIds == null ||
                    (obj.ownerType == AddressOwnerType.CONTACT && obj.ownerId in groupContactIds)
                // Фильтр компании/должности — только вкладка «Работа» (selectedTab==1)
                val matchCompany = companyFilter == null || obj.relatedCompanyId == companyFilter
                val matchPosition = positionFilter == null || obj.relatedPosition == positionFilter
                matchSearch && matchTab && matchRel && matchGroup && matchCompany && matchPosition
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

    // Местоположение пользователя — чтобы карта открывалась там, где он сейчас,
    // а не на первом адресе из данных (был в другом городе).
    // FIX (фидбэк владельца 2026-07-04): «показывает какие-то острова» —
    // lastKnownLatLng брал ТОЛЬКО кэш системы; если ни одно приложение ещё не
    // спрашивало позицию (частый случай после сброса данных/чистой установки),
    // кэш пуст и карта навсегда падала на хардкод-заглушку (Афины, zoom 5 —
    // это и есть «острова»). Теперь при пустом кэше запрашиваем свежую позицию.
    var userLatLng by remember { mutableStateOf<LatLng?>(null) }
    var locateRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(locationPermGranted, locateRequest) {
        if (!locationPermGranted) return@LaunchedEffect
        userLatLng = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            lastKnownLatLng(context) ?: freshLatLng(context)
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
    // карту на нём. Ключ locateRequest — чтобы кнопка «моё местоположение»
    // центрировала карту ЗАНОВО, даже если координаты не изменились.
    //
    // ФИКС (баг найден 2026-07-04, «всё равно остров/кнопка не видна»): ПЕРВОЕ
    // центрирование делалось через cameraState.animate(...), а это вызов В САМУ
    // GoogleMap — если её View ещё не примонтирована (обычная ситуация в момент
    // первой отрисовки), animate() бросает исключение, которое тут же тихо
    // глоталось catch{}. После этого userLatLng больше не менялся → ключ эффекта
    // не менялся → повторной попытки НИКОГДА не было, и камера навсегда
    // оставалась на дефолтной точке (Афины/«остров»), даже когда userLatLng
    // был получен верно. Прямое присвоение cameraState.position — это просто
    // смена состояния, GoogleMap подхватит его сама, когда домонтируется;
    // готовности карты не требует. Анимация (плавный пан) оставлена только
    // для повторных запросов (нажатие кнопки после первого центрирования),
    // где карта уже точно на экране.
    var firstCenterDone by remember { mutableStateOf(false) }
    LaunchedEffect(userLatLng, locateRequest) {
        val u = userLatLng ?: return@LaunchedEffect
        if (selectedItem == null) {
            if (!firstCenterDone) {
                cameraState.position = CameraPosition.fromLatLngZoom(u, 12f)
                firstCenterDone = true
            } else {
                try {
                    cameraState.animate(CameraUpdateFactory.newLatLngZoom(u, 12f))
                } catch (e: Exception) { /* карта ещё инициализируется — не критично для повторных запросов */ }
            }
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
                        // FIX (баг найден 2026-07-04): раньше проверялся ТОЛЬКО
                        // ACCESS_FINE_LOCATION, а locationPermGranted выше допускал
                        // и COARSE — если пользователь дал «примерное» местоположение
                        // (частый выбор в системном диалоге с Android 12), точка и
                        // кнопка «моё местоположение» пропадали молча. Теперь оба
                        // разрешения равноценны, как и везде в этом экране.
                        val safeLocationEnabled = locationPermGranted && try {
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        } catch (e: Exception) { false }

                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraState,
                            // FIX: guard against SecurityException
                            properties = MapProperties(
                                isMyLocationEnabled = safeLocationEnabled
                            ),
                            // ФИКС (аудит 2026-08-11, жалоба владельца: «за этой кнопкой тоже
                            // что-то стоит, закрывает другой кнопкой»): myLocationButtonEnabled
                            // включает СОБСТВЕННУЮ, нативную кнопку-локатор Google Maps SDK —
                            // в фиксированной позиции, вне контроля нашего Compose-layout — ПОВЕРХ
                            // уже существующей своей, Aurelia-стилизованной кнопки «моё местоположение»
                            // в строке поиска (см. ниже, Icons.Default.MyLocation). Две кнопки одной
                            // и той же функции визуально накладывались друг на друга. Нативная
                            // больше не нужна — своя уже делает и запрос разрешения, и recenter.
                            uiSettings = MapUiSettings(
                                myLocationButtonEnabled = false,
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

                    // No-coords notice
                    val noCoordCount = filteredList.count { it.latLng == null }
                    if (noCoordCount > 0) {
                        Card(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            shape    = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10,
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
                    shape     = if (showMapView) com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (showMapView) 12.dp else 8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(36.dp).height(5.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R3).background(AppleTheme.colors.separator))
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
                        // ФИКС (аудит 2026-08-11, жалоба владельца: «текст переполовинен, не
                        // видно» — подтверждено): жёсткий .height(48.dp) на OutlinedTextField
                        // обрезал контент — M3-поле не сжимает свой внутренний padding под
                        // навязанную высоту меньше собственной естественной (~56dp), лишнее
                        // просто клипается родительским Box. .heightIn(min=...) вместо
                        // .height(...) даёт полю вырасти до нужной высоты без обрезания текста,
                        // 48dp по-прежнему гарантирован как МИНИМУМ (тач-таргет не уменьшился).
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        placeholder = { Text(stringResource(R.string.map_search_hint), color = AppleTheme.colors.secondaryLabel) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = AppleTheme.colors.secondaryLabel, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
                        singleLine = true,
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R15,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppleTheme.colors.card,
                            unfocusedContainerColor = AppleTheme.colors.card,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                    Box(
                        Modifier.size(48.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R15).background(AppleTheme.colors.brand).clickable { showMapView = !showMapView },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (showMapView) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Default.Map,
                            contentDescription = if (showMapView) stringResource(R.string.map_list) else stringResource(R.string.map_title),
                            tint = Color.White, modifier = Modifier.size(21.dp)
                        )
                    }
                    // FIX (фидбэк владельца 2026-07-04): «пропала кнопка, где я
                    // нахожусь» — раньше это была плавающая кнопка внутри карты
                    // (align TopStart), но она лежала ПОД этой же панелью поиска
                    // (тоже TopStart, на всю ширину) — панель рисуется позже и
                    // перекрывает её целиком, кнопка была невидима и нетапабельна
                    // с самого начала. Теперь кнопка — часть этого же ряда, не
                    // может быть перекрыта.
                    Box(
                        Modifier.size(48.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R15).background(AppleTheme.colors.card)
                            .clickable {
                                if (!locationPermGranted) {
                                    permLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    selectedItem = null
                                    locateRequest++
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = stringResource(
                                if (!locationPermGranted) R.string.map_grant_location
                                else R.string.map_recenter
                            ),
                            tint = AppleTheme.colors.brand, modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEachIndexed { idx, title ->
                        val active = selectedTab == idx
                        Box(
                            Modifier.height(32.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                                .background(if (active) AppleTheme.colors.brand else AppleTheme.colors.card)
                                .clickable { selectedTab = idx; selectedItem = null }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, color = if (active) Color.White else AppleTheme.colors.label)
                        }
                    }
                }
                // ── Фильтр отношений ОДНОЙ кнопкой-списком (фидбэк владельца
                // 2026-07-03: пролистываемые чипы → «одна кнопочка, выбираешь
                // как фильтр»). Кнопка показывает текущий выбор, тап — шторка.
                if (selectedTab != 2) {
                    val ctxRel = LocalContext.current
                    var showRelSheet by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(8.dp))
                    val filterActive = relFilter != null || groupFilter != null || customRelFilter != null
                    val filterLabel = listOfNotNull(
                        relFilter?.label(ctxRel),
                        customRelFilter,
                        groupFilter?.let { gid -> AppStateStore.groups.firstOrNull { it.id == gid }?.name }
                    ).joinToString(" · ").ifBlank { stringResource(R.string.map_filter_all) }
                    Row(
                        Modifier.height(32.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                            .background(if (filterActive) AppleTheme.colors.brand else AppleTheme.colors.card)
                            .then(
                                if (!filterActive)
                                    Modifier.border(1.dp, AppleTheme.colors.separator, com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                                else Modifier
                            )
                            .clickable { showRelSheet = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.FilterList, null, Modifier.size(15.dp),
                            tint = if (filterActive) Color.White else AppleTheme.colors.secondaryLabel)
                        Text(
                            filterLabel,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (filterActive) Color.White else AppleTheme.colors.secondaryLabel
                        )
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp),
                            tint = if (filterActive) Color.White else AppleTheme.colors.secondaryLabel)
                    }
                    if (showRelSheet) {
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { showRelSheet = false }) {
                            Text(
                                stringResource(R.string.map_filter_title),
                                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(stringResource(R.string.map_filter_title)),
                                fontSize = 20.sp, fontWeight = FontWeight.W700,
                                color = AppleTheme.colors.label,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            (listOf<RelationshipType?>(null) + RelationshipType.values().toList()).forEach { rel ->
                                val selectedRow = relFilter == rel && (rel != null || customRelFilter == null)
                                Row(
                                    Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                                        .clickable {
                                            relFilter = rel
                                            if (rel == null) customRelFilter = null
                                            selectedItem = null; showRelSheet = false
                                        }
                                        .padding(horizontal = 6.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        rel?.label(ctxRel) ?: stringResource(R.string.map_filter_all),
                                        fontSize = 15.sp,
                                        fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedRow) AppleTheme.colors.brand else AppleTheme.colors.label
                                    )
                                    if (selectedRow) Icon(Icons.Default.Check, null,
                                        Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                                }
                                HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                            }
                            // Свои типы отношений («статусы») — раньше на карте вообще
                            // не были доступны как фильтр (фидбэк владельца 2026-07-05).
                            val customTypes = remember(relFilter, groupFilter) {
                                AppStateStore.distinctCustomRelationshipTypes()
                            }
                            if (customTypes.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.filter_custom_status).uppercase(),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                                )
                                customTypes.forEach { ct ->
                                    val selectedRow = customRelFilter == ct
                                    Row(
                                        Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                                            .clickable {
                                                customRelFilter = if (selectedRow) null else ct
                                                selectedItem = null; showRelSheet = false
                                            }
                                            .padding(horizontal = 6.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            ct, fontSize = 15.sp,
                                            fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selectedRow) AppleTheme.colors.brand else AppleTheme.colors.label
                                        )
                                        if (selectedRow) Icon(Icons.Default.Check, null,
                                            Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                                    }
                                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                                }
                            }
                            // Группы (v11) — второй раздел того же листа
                            if (AppStateStore.groups.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.filter_groups).uppercase(),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                                )
                                AppStateStore.groups.sortedBy { it.name.lowercase() }.forEach { g ->
                                    val selectedRow = groupFilter == g.id
                                    Row(
                                        Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                                            .clickable {
                                                groupFilter = if (selectedRow) null else g.id
                                                selectedItem = null; showRelSheet = false
                                            }
                                            .padding(horizontal = 6.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            g.name, fontSize = 15.sp,
                                            fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selectedRow) AppleTheme.colors.brand else AppleTheme.colors.label
                                        )
                                        if (selectedRow) Icon(Icons.Default.Check, null,
                                            Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                                    }
                                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
                // ── Фильтр компании/должности — только вкладка «Работа» (фидбэк
                // владельца 2026-07-04: «в работах в карте должен быть фильтр
                // позиции компании»). Список компаний/должностей строим из
                // текущих рабочих адресов контактов (без учёта самого фильтра).
                if (selectedTab == 1) {
                    var showCompanySheet by remember { mutableStateOf(false) }
                    val workItems = mapObjects.filter {
                        it.ownerType == AddressOwnerType.CONTACT &&
                            it.locationType in listOf(AddressType.WORK, AddressType.OFFICE)
                    }
                    val companyOptions = workItems
                        .mapNotNull { it.relatedCompanyId?.let { id -> id to (it.relatedCompanyName ?: "") } }
                        .distinctBy { it.first }
                        .sortedBy { it.second.lowercase() }
                    val positionOptions = workItems
                        .mapNotNull { it.relatedPosition?.takeIf { p -> p.isNotBlank() } }
                        .distinct()
                        .sorted()
                    Spacer(Modifier.height(8.dp))
                    val companyFilterActive = companyFilter != null || positionFilter != null
                    val companyFilterLabel = listOfNotNull(
                        companyFilter?.let { id -> companyOptions.firstOrNull { it.first == id }?.second },
                        positionFilter
                    ).joinToString(" · ").ifBlank { stringResource(R.string.map_filter_by_company) }
                    Row(
                        Modifier.height(32.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                            .background(if (companyFilterActive) AppleTheme.colors.brand else AppleTheme.colors.card)
                            .then(
                                if (!companyFilterActive)
                                    Modifier.border(1.dp, AppleTheme.colors.separator, com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large)
                                else Modifier
                            )
                            .clickable { showCompanySheet = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Work, null, Modifier.size(15.dp),
                            tint = if (companyFilterActive) Color.White else AppleTheme.colors.secondaryLabel)
                        Text(
                            companyFilterLabel,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (companyFilterActive) Color.White else AppleTheme.colors.secondaryLabel
                        )
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp),
                            tint = if (companyFilterActive) Color.White else AppleTheme.colors.secondaryLabel)
                    }
                    if (showCompanySheet) {
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { showCompanySheet = false }) {
                            Text(
                                stringResource(R.string.map_filter_by_company),
                                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(stringResource(R.string.map_filter_by_company)),
                                fontSize = 20.sp, fontWeight = FontWeight.W700,
                                color = AppleTheme.colors.label,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                                    .clickable {
                                        companyFilter = null; positionFilter = null
                                        selectedItem = null; showCompanySheet = false
                                    }
                                    .padding(horizontal = 6.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    stringResource(R.string.map_filter_all),
                                    fontSize = 15.sp,
                                    fontWeight = if (!companyFilterActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!companyFilterActive) AppleTheme.colors.brand else AppleTheme.colors.label
                                )
                                if (!companyFilterActive) Icon(Icons.Default.Check, null,
                                    Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                            }
                            HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                            if (companyOptions.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.map_filter_companies_section).uppercase(),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                                )
                                companyOptions.forEach { (id, name) ->
                                    val selectedRow = companyFilter == id
                                    Row(
                                        Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                                            .clickable {
                                                companyFilter = if (selectedRow) null else id
                                                selectedItem = null; showCompanySheet = false
                                            }
                                            .padding(horizontal = 6.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            name, fontSize = 15.sp,
                                            fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selectedRow) AppleTheme.colors.brand else AppleTheme.colors.label
                                        )
                                        if (selectedRow) Icon(Icons.Default.Check, null,
                                            Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                                    }
                                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                                }
                            }
                            if (positionOptions.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.map_filter_positions_section).uppercase(),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp, color = AppleTheme.colors.goldLabel,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                                )
                                positionOptions.forEach { pos ->
                                    val selectedRow = positionFilter == pos
                                    Row(
                                        Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                                            .clickable {
                                                positionFilter = if (selectedRow) null else pos
                                                selectedItem = null; showCompanySheet = false
                                            }
                                            .padding(horizontal = 6.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            pos, fontSize = 15.sp,
                                            fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selectedRow) AppleTheme.colors.brand else AppleTheme.colors.label
                                        )
                                        if (selectedRow) Icon(Icons.Default.Check, null,
                                            Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                                    }
                                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Пин на карте в стиле Aurelia: контакт — круглый градиент-аватар с инициалами
 * (цвет по хешу ownerId через [AureliaAvatars.brushFor] — как в списке/карточке),
 * компания — плитка с иконкой на брендовом фоне. Выбранный элемент — крупнее,
 * с золотой окантовкой вместо обычной card-рамки.
 */
@Composable
private fun MapMarkerPin(obj: MapLocationItem, selected: Boolean) {
    val size = if (selected) 52.dp else 44.dp
    val ringColor = if (selected) AureliaTheme.colors.gold else AppleTheme.colors.card
    val ringWidth = if (selected) 3.dp else 2.5.dp
    // Пин по макету: круг/плитка с кольцом + ЗАОСТРЕНИЕ-хвостик вниз
    // (фидбэк владельца: «должно быть заострение, как будто это пин»).
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (obj.ownerType == AddressOwnerType.COMPANY) {
            Box(
                modifier = Modifier.size(size)
                    .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                    .background(AppleTheme.colors.brand)
                    .border(ringWidth, ringColor, com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium),
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
                    .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor(obj.ownerId))
                    .border(ringWidth, ringColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.8).sp)
            }
        }
        // Хвостик-заострение цвета кольца
        androidx.compose.foundation.Canvas(Modifier.size(14.dp, 9.dp)) {
            val w = drawContext.size.width
            val h = drawContext.size.height
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h); close()
            }
            drawPath(p, ringColor)
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
        shape     = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium,
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
        shape     = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
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
                        modifier = Modifier.size(48.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
                            .background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Business, null, tint = AppleTheme.colors.brand)
                    }
                } else {
                    // Аватар в стиле Aurelia: градиент по хешу ownerId + инициалы
                    val initials = item.title.split(" ")
                        .mapNotNull { it.firstOrNull()?.toString() }
                        .take(2).joinToString("")
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor(item.ownerId)),
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

/**
 * Свежий запрос позиции, когда системный кэш (lastKnownLatLng) пуст — частый
 * случай на свежей установке/после сброса данных, если ни одно приложение ещё
 * не спрашивало геолокацию. Без такого запроса карта навсегда оставалась на
 * хардкод-заглушке (фидбэк владельца 2026-07-04: «показывает какие-то острова»).
 * До 8 секунд ждём первый апдейт с любого доступного провайдера, затем сдаёмся.
 */
private suspend fun freshLatLng(context: android.content.Context): LatLng? {
    return try {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) return null
        kotlinx.coroutines.withTimeoutOrNull(8000) {
            kotlinx.coroutines.suspendCancellableCoroutine<LatLng?> { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        if (cont.isActive) cont.resume(LatLng(location.latitude, location.longitude)) {}
                        try { lm.removeUpdates(this) } catch (e: Exception) { }
                    }
                }
                // ФИКС (баг найден 2026-07-04): раньше ОДИН try/catch оборачивал
                // ВЕСЬ цикл — если первый провайдер (обычно "gps", требует FINE)
                // кидал SecurityException из-за того, что выдано только
                // «Приблизительно» (COARSE), весь запрос падал в null, даже не
                // пытаясь спросить "network" (которому достаточно COARSE). Теперь
                // каждый провайдер пробуется независимо, как уже сделано в
                // lastKnownLatLng() выше.
                var anyRequested = false
                for (provider in providers) {
                    try {
                        @Suppress("MissingPermission")
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
                        anyRequested = true
                    } catch (e: SecurityException) { /* пробуем следующий провайдер */ }
                }
                if (!anyRequested) {
                    if (cont.isActive) cont.resume(null) {}
                } else {
                    cont.invokeOnCancellation {
                        try { lm.removeUpdates(listener) } catch (e: Exception) { }
                    }
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}
