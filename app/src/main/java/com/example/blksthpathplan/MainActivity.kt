package com.example.blksthpathplan

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.example.blksthpathplan.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), ZmqManager.ZmqListener {

    private lateinit var zmqManager: ZmqManager
    private var connectionStatus by mutableStateOf("OFFLINE")
    private val terminalLogs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        zmqManager = ZmqManager(this)
        enableEdgeToEdge()
        addLog("SYSTEM SIAP!!! (ZMQ MODE)")
        setContent {
            BLKSTHPathPlanTheme {
                val isDark = isSystemInDarkTheme()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (isDark) Color.Black else Color.White
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            painter = painterResource(id = if (isDark) R.drawable.logo_dark else R.drawable.logo_light),
                            contentDescription = null,
                            modifier = Modifier
                                .size(320.dp)
                                .align(Alignment.Center)
                                .alpha(if (isDark) 0.08f else 0.12f),
                            contentScale = ContentScale.Fit
                        )

                        GundamMissionScreen(
                            status = connectionStatus,
                            logs = terminalLogs,
                            isDark = isDark,
                            onConnect = { ip, port ->
                                val url = "tcp://$ip:$port"
                                addLog("CONNECTING ZMQ TO: $url")
                                zmqManager.connect(url)
                            },
                            onDisconnect = {
                                addLog("DISCONNECTING ZMQ...")
                                zmqManager.disconnect()
                            },
                            onSend = { team, grids -> sendKfsData(team, grids) }
                        )
                    }
                }
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        terminalLogs.add("[$timestamp] $message")
        if (terminalLogs.size > 30) terminalLogs.removeAt(0)
    }

    private fun sendKfsData(team: String, grids: List<Int>) {
        if (connectionStatus != "ONLINE") {
            addLog("ERROR: SYSTEM OFFLINE")
            Toast.makeText(this, "SYSTEM OFFLINE", Toast.LENGTH_SHORT).show()
            return
        }

        val prefix = if (team == "BLUE") "B" else "R"
        val gridLabels = grids.map { "$prefix$it" }
        addLog("EXECUTE: $team TEAM | KFS LOCATIONS: $gridLabels")

        // Buat payload data
        val payload = JSONObject()
        payload.put("team", team)
        val gridArray = JSONArray()
        grids.forEach { gridArray.put(it) }
        payload.put("kfs_grids", gridArray)
        payload.put("data", "$team KFS Location $gridLabels")

        // Kirim via ZMQ dengan topic "kfs_config"
        zmqManager.sendMessage("kfs_config", payload.toString())
        addLog("KFS POSITION DATA SENT")
    }

    override fun onConnected() {
        runOnUiThread {
            connectionStatus = "ONLINE"
            addLog("ZMQ COMMS ESTABLISHED")
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            connectionStatus = "OFFLINE"
            addLog("ZMQ COMMS TERMINATED")
        }
    }

    override fun onMessageReceived(topic: String, message: String) {
        runOnUiThread {
            addLog("[$topic] ROBOT: $message")
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            connectionStatus = "ERROR"
            addLog("ZMQ FAILURE: $error")
            Toast.makeText(this, "ZMQ FAILURE: $error", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun GundamMissionScreen(
    status: String,
    logs: List<String>,
    isDark: Boolean,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onSend: (String, List<Int>) -> Unit
) {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("RobotSettings", Context.MODE_PRIVATE) }

    var ipAddress by remember { mutableStateOf(sharedPref.getString("saved_ip", "") ?: "") }
    var portNumber by remember { mutableStateOf(sharedPref.getString("saved_port", "5555") ?: "5555") }

    var savedTeam by remember { mutableStateOf(sharedPref.getString("last_team", "NONE") ?: "NONE") }
    val savedGridsJson = sharedPref.getString("last_grids", "[]") ?: "[]"
    val initialGrids = remember(savedGridsJson) {
        val list = mutableListOf<Int>()
        val array = JSONArray(savedGridsJson)
        for (i in 0 until array.length()) list.add(array.getInt(i))
        list
    }
    var selectedGrids by remember { mutableStateOf(initialGrids.toList()) }

    val showGridDialog = remember { mutableStateOf(false) }
    val activeTeamForDialog = remember { mutableStateOf("NONE") }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (showGridDialog.value) {
        ForestGridDialog(
            team = activeTeamForDialog.value,
            initialSelected = if (savedTeam == activeTeamForDialog.value) selectedGrids else emptyList(),
            onDismiss = { showGridDialog.value = false },
            onSave = { team, grids ->
                selectedGrids = grids
                savedTeam = team
                sharedPref.edit {
                    putString("last_team", team)
                    putString("last_grids", JSONArray(grids).toString())
                }
                showGridDialog.value = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SystemHeader(status, isDark)
                    Spacer(modifier = Modifier.height(12.dp))
                    ConnectionPanel(
                        ip = ipAddress,
                        onIpChange = {
                            ipAddress = it
                            sharedPref.edit { putString("saved_ip", it) }
                        },
                        port = portNumber,
                        onPortChange = {
                            portNumber = it
                            sharedPref.edit { putString("saved_port", it) }
                        },
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        isDark = isDark
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TerminalDisplay(logs, isDark, modifier = Modifier.weight(1f))
                }
                Column(modifier = Modifier.weight(1.3f)) {
                    TeamButtons(savedTeam, selectedGrids, isDark) { team ->
                        activeTeamForDialog.value = team
                        showGridDialog.value = true
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    ActionButtons(
                        team = savedTeam,
                        grids = selectedGrids,
                        onReset = {
                            selectedGrids = emptyList()
                            savedTeam = "NONE"
                            sharedPref.edit {
                                putString("last_team", "NONE")
                                putString("last_grids", "[]")
                            }
                        },
                        onSend = { onSend(savedTeam, selectedGrids) }
                    )
                }
            }
        } else {
            Column(modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)) {
                SystemHeader(status, isDark)
                Spacer(modifier = Modifier.height(12.dp))
                ConnectionPanel(
                    ip = ipAddress,
                    onIpChange = { ipAddress = it; sharedPref.edit { putString("saved_ip", it) } },
                    port = portNumber,
                    onPortChange = { portNumber = it; sharedPref.edit { putString("saved_port", it) } },
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    isDark = isDark
                )
                Spacer(modifier = Modifier.height(16.dp))
                TeamButtons(savedTeam, selectedGrids, isDark) { team ->
                    activeTeamForDialog.value = team
                    showGridDialog.value = true
                }
                Spacer(modifier = Modifier.height(16.dp))
                TerminalDisplay(logs, isDark, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(16.dp))
                ActionButtons(team = savedTeam, grids = selectedGrids, onReset = { selectedGrids = emptyList(); savedTeam = "NONE"; sharedPref.edit { putString("last_team", "NONE"); putString("last_grids", "[]") } }, onSend = { onSend(savedTeam, selectedGrids) })
            }
        }
    }
}

@Composable
fun ForestGridDialog(
    team: String,
    initialSelected: List<Int>,
    onDismiss: () -> Unit,
    onSave: (String, List<Int>) -> Unit
) {
    var tempSelected by remember { mutableStateOf(initialSelected) }
    val teamColor = if (team == "BLUE") GundamBlue else GundamRed
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.9f else 0.95f)
                .wrapContentHeight(),
            color = GundamDarkGrey,
            shape = CutCornerShape(16.dp),
            border = BorderStroke(2.dp, teamColor)
        ) {
            if (isLandscape) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1.1f), contentAlignment = Alignment.Center) {
                        GridSelectionContent(team, tempSelected) { tempSelected = it }
                    }
                    
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "PENENTU KFS: $team",
                            color = teamColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        
                        Surface(
                            color = Color.Black.copy(alpha = 0.3f),
                            shape = CutCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("LETAK KFS:", color = teamColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                KfsLocationDisplay(grids = tempSelected, team = team, teamColor = teamColor)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GundamButton("BACK", GundamLightGrey, onClick = onDismiss, modifier = Modifier.weight(1f), compact = true)
                            GundamButton("SAVE", teamColor, onClick = { onSave(team, tempSelected) }, modifier = Modifier.weight(1f), compact = true)
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PENENTU KFS: $team",
                        color = teamColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    GridSelectionContent(team, tempSelected) { tempSelected = it }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Surface(
                        color = Color.Black.copy(alpha = 0.3f),
                        shape = CutCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("LETAK KFS:", color = teamColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            KfsLocationDisplay(grids = tempSelected, team = team, teamColor = teamColor)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GundamButton("BACK", GundamLightGrey, onClick = onDismiss, modifier = Modifier.weight(1f), compact = true)
                        GundamButton("SAVE", teamColor, onClick = { onSave(team, tempSelected) }, modifier = Modifier.weight(1f), compact = true)
                    }
                }
            }
        }
    }
}

@Composable
fun GridSelectionContent(
    team: String,
    tempSelected: List<Int>,
    onUpdateSelected: (List<Int>) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(4.dp)
    ) {
        for (row in 0 until 4) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.wrapContentWidth()
            ) {
                for (col in 0 until 3) {
                    val displayIndex = if (team == "BLUE") {
                        (3 - row) * 3 + (col + 1)
                    } else {
                        (3 - row) * 3 + (3 - col)
                    }

                    val isSelected = tempSelected.contains(displayIndex)
                    val labelPrefix = if (team == "BLUE") "B" else "R"
                    val label = "$labelPrefix$displayIndex"

                    val gridColor = when (displayIndex) {
                        1, 3, 5, 7, 9, 11 -> Color(0xFF386633)
                        2, 4, 10, 12 -> Color(0xFF1B3319)
                        6, 8 -> Color(0xFF9DB35D)
                        else -> Color(0xFF386633)
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .aspectRatio(1f)
                            .clip(CutCornerShape(4.dp))
                            .background(gridColor)
                            .border(
                                if (isSelected) 2.5.dp else 1.dp,
                                if (isSelected) Color.White else Color.White.copy(alpha = 0.2f),
                                CutCornerShape(4.dp)
                            )
                            .clickable {
                                if (isSelected) {
                                    onUpdateSelected(tempSelected.filter { it != displayIndex })
                                } else {
                                    onUpdateSelected(tempSelected + displayIndex)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .border(1.dp, Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Icon(
                                    Icons.Default.Check,
                                    null,
                                    modifier = Modifier.size(8.dp),
                                    tint = Color.White
                                )
                            }
                            Text(
                                label,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TeamButtons(
    selectedTeam: String,
    selectedGrids: List<Int>,
    isDark: Boolean,
    onTeamSelect: (String) -> Unit
) {
    val textColor = if (isDark) GundamWhite else Color.Black
    Column {
        Text("SELECT TEAM:", color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GundamButton(
                text = "BLUE TEAM",
                color = GundamBlue,
                onClick = { onTeamSelect("BLUE") },
                modifier = Modifier
                    .weight(1f)
                    .border(
                        if (selectedTeam == "BLUE") 2.dp else 0.dp,
                        GundamNeonGreen,
                        CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
                    )
            )
            GundamButton(
                text = "RED TEAM",
                color = GundamRed,
                onClick = { onTeamSelect("RED") },
                modifier = Modifier
                    .weight(1f)
                    .border(
                        if (selectedTeam == "RED") 2.dp else 0.dp,
                        GundamNeonGreen,
                        CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
                    )
            )
        }
        if (selectedTeam != "NONE") {
            Spacer(modifier = Modifier.height(8.dp))
            val teamColor = if (selectedTeam == "BLUE") GundamBlue else GundamRed
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.05f), CutCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text("Active Mode: $selectedTeam Team", color = textColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("LETAK KFS:", color = textColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                KfsLocationDisplay(grids = selectedGrids, team = selectedTeam, teamColor = teamColor)
            }
        }
    }
}

@Composable
fun KfsLocationDisplay(
    grids: List<Int>,
    team: String,
    teamColor: Color,
    modifier: Modifier = Modifier
) {
    val prefix = if (team == "BLUE") "B" else "R"
    
    if (grids.isEmpty()) {
        Text("BELUM DIPILIH", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        return
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        grids.forEachIndexed { index, grid ->
            Surface(
                color = teamColor.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, teamColor),
                shape = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)
            ) {
                Text(
                    text = "$prefix$grid",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = teamColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (index < grids.size - 1) {
                Text(
                    text = " , ",
                    color = teamColor.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TerminalDisplay(logs: List<String>, isDark: Boolean, modifier: Modifier = Modifier) {
    val borderColor = if (isDark) GundamBlue else GundamLightGrey
    val terminalColor = Color.Black.copy(alpha = 0.9f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(terminalColor, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("L0G_T3RM1N4L", color = GundamNeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Box(modifier = Modifier
                .size(6.dp)
                .background(GundamNeonGreen, CutCornerShape(1.dp)))
        }
        HorizontalDivider(color = GundamNeonGreen.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        val scrollState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                scrollState.animateScrollToItem(logs.size - 1)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), state = scrollState, contentPadding = PaddingValues(bottom = 4.dp)) {
            items(logs) { log ->
                Text(text = "> $log", color = GundamNeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
fun SystemHeader(status: String, isDark: Boolean) {
    val textColor = if (isDark) GundamWhite else Color.Black
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, GundamBlue, CutCornerShape(topEnd = 16.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = if (isDark) R.drawable.logo_dark else R.drawable.logo_light),
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(GundamWhite.copy(alpha = 0.1f))
                    .padding(2.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("BLAKASUTHA KFS POSITIONING", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                Text("v1.0", color = GundamLightGrey, fontSize = 10.sp)
            }
        }
        Box(
            modifier = Modifier
                .size(width = 75.dp, height = 22.dp)
                .background(
                    if (status == "ONLINE") GundamNeonGreen else GundamRed,
                    CutCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(status, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 10.sp)
        }
    }
}

@Composable
fun ConnectionPanel(
    ip: String, onIpChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    isDark: Boolean
) {
    val textColor = if (isDark) GundamWhite else Color.Black
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ip, onValueChange = onIpChange,
                label = { Text("IP", color = GundamBlue, fontSize = 10.sp) },
                modifier = Modifier.weight(0.65f),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = GundamBlue, unfocusedBorderColor = GundamLightGrey),
                shape = CutCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )
            OutlinedTextField(
                value = port, onValueChange = onPortChange,
                label = { Text("PORT", color = GundamBlue, fontSize = 10.sp) },
                modifier = Modifier.weight(0.35f),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = GundamBlue, unfocusedBorderColor = GundamLightGrey),
                shape = CutCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GundamButton(text = "CONNECT", color = Color(0xFF4CAF50), onClick = { onConnect(ip, port) }, modifier = Modifier.weight(1f), compact = true)
            GundamButton(text = "DISCONNECT", color = GundamLightGrey, onClick = onDisconnect, modifier = Modifier.weight(1f), compact = true)
        }
    }
}

@Composable
fun ActionButtons(
    team: String,
    grids: List<Int>,
    onReset: () -> Unit,
    onSend: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GundamButton("RESET", GundamLightGrey, onReset, Modifier.weight(1f))
        GundamButton(
            text = "KIRIM KFS",
            color = GundamYellow,
            onClick = onSend,
            modifier = Modifier.weight(1f),
            textColor = Color.Black,
            enabled = team != "NONE" && grids.isNotEmpty()
        )
    }
}

@Composable
fun GundamButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, textColor: Color = Color.White, enabled: Boolean = true, compact: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier.height(if (compact) 40.dp else 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.3f)),
        shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
        enabled = enabled
    ) {
        Text(text, color = if (enabled) textColor else textColor.copy(alpha = 0.5f), fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
    }
}
