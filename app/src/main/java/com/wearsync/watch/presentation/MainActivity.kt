package com.wearsync.watch.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearsync.watch.R
import com.wearsync.watch.ancs.ConnectionStatus
import com.wearsync.watch.presentation.screens.ActiveDeviceScreen
import com.wearsync.watch.presentation.screens.ConnectDevicesScreen
import com.wearsync.watch.presentation.theme.WearSyncColors
import com.wearsync.watch.presentation.theme.WearSyncTheme
import com.wearsync.watch.service.WearSyncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_CONNECT_DEVICES = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearSyncTheme {
                WearSyncRoot(
                    onStartService = { WearSyncService.start(this) }
                )
            }
        }
    }
}

@Composable
private fun WearSyncRoot(
    onStartService: () -> Unit
) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = requiredPermissions.all { perm ->
            results[perm] == true ||
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermissions) {
            onStartService()
        }
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            onStartService()
        }
    }

    if (!hasPermissions) {
        PermissionScreen(
            onGrantClick = { permissionLauncher.launch(requiredPermissions) }
        )
    } else {
        WearSyncPagerScreen()
    }
}

@Composable
private fun WearSyncPagerScreen() {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    var service by remember { mutableStateOf(WearSyncService.activeInstance) }
    LaunchedEffect(Unit) {
        while (service == null) {
            service = WearSyncService.activeInstance
            if (service == null) delay(100)
        }
    }

    val activeService = service
    val bleManager = activeService?.bleManager

    val connectionState by (bleManager?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(ConnectionStatus.DISCONNECTED) })
    val activeDeviceName by (bleManager?.activeDeviceName?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) })
    val activeDeviceAddress by (bleManager?.activeDeviceAddress?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) })
    val statusHint by (bleManager?.statusHint?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) })
    val devicesList by (bleManager?.devicesList?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                MainActivity.PAGE_HOME -> ActiveDeviceScreen(
                    deviceName = activeDeviceName,
                    connectionStatus = connectionState,
                    statusHint = statusHint,
                    onDisconnectClick = { bleManager?.disconnect(forgetActive = false) },
                    onReconnectClick = { bleManager?.reconnectActiveDevice() },
                    onForgetClick = {
                        activeDeviceAddress?.let { addr -> bleManager?.forgetDevice(addr) }
                    },
                    onFindIphoneClick = {
                        bleManager?.startScan()
                        scope.launch { pagerState.animateScrollToPage(MainActivity.PAGE_CONNECT_DEVICES) }
                    }
                )

                MainActivity.PAGE_CONNECT_DEVICES -> ConnectDevicesScreen(
                    devices = devicesList,
                    isScanning = connectionState == ConnectionStatus.SCANNING,
                    onDeviceClick = { item ->
                        if (item.status == ConnectionStatus.CONNECTED) {
                            scope.launch { pagerState.animateScrollToPage(MainActivity.PAGE_HOME) }
                        } else {
                            bleManager?.connectByAddress(item.address)
                            scope.launch { pagerState.animateScrollToPage(MainActivity.PAGE_HOME) }
                        }
                    },
                    onForgetDevice = { item -> bleManager?.forgetDevice(item.address) },
                    onScanClick = { bleManager?.startScan() }
                )
            }
        }

        // Minimal 2-dot page indicator matching the two-panel UI
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) WearSyncColors.DotActive else WearSyncColors.DotInactive
                        )
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_apple),
            contentDescription = "WearSync",
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "WearSync",
            color = WearSyncColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Allow Bluetooth & Notifications to sync iPhone alerts.",
            color = WearSyncColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .widthIn(min = 126.dp)
                .height(42.dp)
                .clip(CircleShape)
                .background(WearSyncColors.PillSurface)
                .clickable(onClick = onGrantClick)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Allow",
                color = WearSyncColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
