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
import androidx.compose.foundation.shape.CutCornerShape
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
    private val pathPlanner = PathPlanner()
    private var connectionStatus by mutableStateOf("OFFLINE")
    private val terminalLogs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        zmqManager = ZmqManager(this)
        enableEdgeToEdge()
        addLog("SYSTEM SIAP DULLL!!!")
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
                            onSend = { team, r2Grids, fakeGrid -> 
                                executeAndSendMission(team, r2Grids, fakeGrid)
                            }
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

    private fun executeAndSendMission(team: String, r2Grids: List<Int>, fakeGrid: Int?) {
        addLog("CALCULATING PATH...")
        val prefix = if (team == "BLUE") "B" else "R"
        
        val result = pathPlanner.findPath(r2Grids, fakeGrid)
        
        if (result != null) {
            val (path, taken) = result
            addLog("PATH FOUND! Taken KFS: $taken")
            
            val pathDataArray = JSONArray()
            val pathLogList = mutableListOf<String>()
            
            path.forEach { node ->
                val coord = pathPlanner.coords[node] ?: Pair(0, 0)
                val stepText = "$prefix$node(${coord.first},${coord.second})"
                pathLogList.add(stepText)
                
                val stepObj = JSONObject().apply {
                    put("node", node)
                    put("x", coord.first)
                    put("y", coord.second)
                }
                pathDataArray.put(stepObj)
            }
            
            // Format output like: {B2(1,0), B1(0,0), ...}
            val formattedPathLog = pathLogList.joinToString(separator = ", ", prefix = "{", postfix = "}")
            addLog("Path: $formattedPathLog")

            if (connectionStatus == "ONLINE") {
                val payload = JSONObject().apply {
                    put("team", team)
                    put("path_points", pathDataArray)
                    put("total_steps", path.size)
                }
                
                zmqManager.sendMessage("kfs_config", payload.toString())
                addLog("MISSION DATA SENT TO ROBOT")
            } else {
                addLog("WARNING: ROBOT OFFLINE. Path calculated locally.")
            }
        } else {
            addLog("ERROR: NO SOLUTION FOUND!")
            Toast.makeText(this, "NO SOLUTION FOUND!", Toast.LENGTH_LONG).show()
        }
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
    onSend: (String, List<Int>, Int?) -> Unit
) {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("RobotSettings", Context.MODE_PRIVATE) }

    var ipAddress by remember { mutableStateOf(sharedPref.getString("saved_ip", "") ?: "") }
    var portNumber by remember { mutableStateOf(sharedPref.getString("saved_port", "5555") ?: "5555") }

    var savedTeam by remember { mutableStateOf(sharedPref.getString("last_team", "NONE") ?: "NONE") }
    
    val savedR2Json = sharedPref.getString("last_r2", "[]") ?: "[]"
    val savedFake = sharedPref.getInt("last_fake", -1).let { if (it == -1) null else it }

    val initialR2 = remember(savedR2Json) {
        val list = mutableListOf<Int>()
        val array = JSONArray(savedR2Json)
        for (i in 0 until array.length()) list.add(array.getInt(i))
        list
    }
    
    var r2Selected by remember { mutableStateOf(initialR2.toList()) }
    var fakeSelected by remember { mutableStateOf(savedFake) }

    val showGridDialog = remember { mutableStateOf(false) }
    val activeTeamForDialog = remember { mutableStateOf("NONE") }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (showGridDialog.value) {
        ForestGridDialog(
            team = activeTeamForDialog.value,
            initialR2 = if (savedTeam == activeTeamForDialog.value) r2Selected else emptyList(),
            initialFake = if (savedTeam == activeTeamForDialog.value) fakeSelected else null,
            onDismiss = { showGridDialog.value = false },
            onSave = { team, r2, fake ->
                r2Selected = r2
                fakeSelected = fake
                savedTeam = team
                sharedPref.edit {
                    putString("last_team", team)
                    putString("last_r2", JSONArray(r2).toString())
                    putInt("last_fake", fake ?: -1)
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
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SystemHeader(status, isDark)
                    Spacer(modifier = Modifier.height(12.dp))
                    ConnectionPanel(ipAddress, { ipAddress = it; sharedPref.edit { putString("saved_ip", it) } }, portNumber, { portNumber = it; sharedPref.edit { putString("saved_port", it) } }, onConnect, onDisconnect, isDark)
                    Spacer(modifier = Modifier.height(16.dp))
                    TerminalDisplay(logs, isDark, modifier = Modifier.weight(1f))
                }
                Column(modifier = Modifier.weight(1.3f)) {
                    TeamButtons(savedTeam, r2Selected, fakeSelected, isDark) { team ->
                        activeTeamForDialog.value = team
                        showGridDialog.value = true
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    ActionButtons(savedTeam, r2Selected, fakeSelected, onReset = {
                        r2Selected = emptyList(); fakeSelected = null; savedTeam = "NONE"
                        sharedPref.edit { putString("last_team", "NONE"); putString("last_r2", "[]"); putInt("last_fake", -1) }
                    }, onSend = { onSend(savedTeam, r2Selected, fakeSelected) })
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                SystemHeader(status, isDark)
                Spacer(modifier = Modifier.height(12.dp))
                ConnectionPanel(ipAddress, { ipAddress = it; sharedPref.edit { putString("saved_ip", it) } }, portNumber, { portNumber = it; sharedPref.edit { putString("saved_port", it) } }, onConnect, onDisconnect, isDark)
                Spacer(modifier = Modifier.height(16.dp))
                TeamButtons(savedTeam, r2Selected, fakeSelected, isDark) { team ->
                    activeTeamForDialog.value = team
                    showGridDialog.value = true
                }
                Spacer(modifier = Modifier.height(16.dp))
                TerminalDisplay(logs, isDark, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(16.dp))
                ActionButtons(savedTeam, r2Selected, fakeSelected, onReset = {
                    r2Selected = emptyList(); fakeSelected = null; savedTeam = "NONE"
                    sharedPref.edit { putString("last_team", "NONE"); putString("last_r2", "[]"); putInt("last_fake", -1) }
                }, onSend = { onSend(savedTeam, r2Selected, fakeSelected) })
            }
        }
    }
}

@Composable
fun ForestGridDialog(
    team: String,
    initialR2: List<Int>,
    initialFake: Int?,
    onDismiss: () -> Unit,
    onSave: (String, List<Int>, Int?) -> Unit
) {
    var tempR2 by remember { mutableStateOf(initialR2) }
    var tempFake by remember { mutableStateOf(initialFake) }
    val teamColor = if (team == "BLUE") GundamBlue else GundamRed
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isLandscape) 0.9f else 0.95f).wrapContentHeight(),
            color = GundamDarkGrey, shape = CutCornerShape(16.dp), border = BorderStroke(2.dp, teamColor)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("LAYOUT KFS: $team", color = teamColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))

                GridSelectionContent(team, tempR2, tempFake) { r2, fake ->
                    tempR2 = r2
                    tempFake = fake
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(color = Color.Black.copy(alpha = 0.3f), shape = CutCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("SUMMARY (R2 & FAKE):", color = teamColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        KfsLocationDisplay(tempR2, tempFake, team, teamColor)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GundamButton("BACK", GundamLightGrey, onClick = onDismiss, modifier = Modifier.weight(1f), compact = true)
                    GundamButton("SAVE", teamColor, onClick = { onSave(team, tempR2, tempFake) }, modifier = Modifier.weight(1f), compact = true)
                }
            }
        }
    }
}

@Composable
fun GridSelectionContent(
    team: String,
    r2Selected: List<Int>,
    fakeSelected: Int?,
    onUpdate: (List<Int>, Int?) -> Unit
) {
    val prefix = if (team == "BLUE") "B" else "R"
    val instruction = when {
        r2Selected.size < 4 -> "PILIH 4 TITIK UNTUK R2 KFS (${r2Selected.size}/4)"
        fakeSelected == null -> "PILIH 1 TITIK UNTUK FAKE KFS (0/1)"
        else -> "INPUT SELESAI!"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(instruction, color = GundamWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        
        for (row in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 3) {
                    val displayIndex = if (team == "BLUE") (3 - row) * 3 + (col + 1) else (3 - row) * 3 + (3 - col)
                    val isR2 = r2Selected.contains(displayIndex)
                    val isFake = fakeSelected == displayIndex
                    
                    val gridHeight = when (displayIndex) {
                        2, 4, 10, 12 -> 20
                        1, 3, 5, 7, 9, 11 -> 40
                        6, 8 -> 60
                        else -> 40
                    }
                    val baseColor = when (gridHeight) {
                        20 -> Color(0xFF346739)
                        40 -> Color(0xFF79AE6F)
                        60 -> Color(0xFFF2EDC2)
                        else -> Color(0xFF79AE6F)
                    }

                    val btnBgColor = when {
                        isR2 -> Color(0xFFF1C232)
                        isFake -> Color(0xFFE06666)
                        else -> baseColor
                    }

                    val x = if (team == "BLUE") col else 2 - col
                    val y = 3 - row

                    Box(
                        modifier = Modifier.size(68.dp).clip(CutCornerShape(4.dp)).background(btnBgColor)
                            .border(if (isR2 || isFake) 2.dp else 1.dp, Color.White.copy(alpha = if (isR2 || isFake) 1f else 0.2f))
                            .clickable {
                                if (isR2) onUpdate(r2Selected - displayIndex, fakeSelected)
                                else if (isFake) onUpdate(r2Selected, null)
                                else {
                                    if (r2Selected.size < 4) onUpdate(r2Selected + displayIndex, fakeSelected)
                                    else if (fakeSelected == null) onUpdate(r2Selected, displayIndex)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$prefix$displayIndex", color = if (isR2 || isFake || gridHeight == 60) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("($x,$y)", color = if (isR2 || isFake || gridHeight == 60) Color.Black.copy(0.6f) else Color.White.copy(0.6f), fontSize = 9.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun TeamButtons(selectedTeam: String, r2: List<Int>, fake: Int?, isDark: Boolean, onTeamSelect: (String) -> Unit) {
    val textColor = if (isDark) GundamWhite else Color.Black
    Column {
        Text("SELECT TEAM:", color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GundamButton("BLUE TEAM", GundamBlue, { onTeamSelect("BLUE") }, Modifier.weight(1f), border = if (selectedTeam == "BLUE") 2.dp else 0.dp)
            GundamButton("RED TEAM", GundamRed, { onTeamSelect("RED") }, Modifier.weight(1f), border = if (selectedTeam == "RED") 2.dp else 0.dp)
        }
        if (selectedTeam != "NONE") {
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.05f), CutCornerShape(4.dp)).padding(8.dp)) {
                Text("Active: $selectedTeam Team", color = textColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                KfsLocationDisplay(r2, fake, selectedTeam, if (selectedTeam == "BLUE") GundamBlue else GundamRed)
            }
        }
    }
}

@Composable
fun KfsLocationDisplay(r2: List<Int>, fake: Int?, team: String, teamColor: Color) {
    val prefix = if (team == "BLUE") "B" else "R"
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R2: ", color = Color(0xFFF1C232), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (r2.isEmpty()) Text("NONE", color = Color.Gray, fontSize = 11.sp)
            else r2.forEach { Text("$prefix$it ", color = teamColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FAKE: ", color = Color(0xFFE06666), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(if (fake != null) "$prefix$fake" else "NONE", color = teamColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun TerminalDisplay(logs: List<String>, isDark: Boolean, modifier: Modifier = Modifier) {
    val borderColor = if (isDark) GundamBlue else GundamLightGrey
    Column(modifier = modifier.fillMaxWidth().border(1.dp, borderColor, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)).background(Color.Black.copy(alpha = 0.9f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)).padding(8.dp)) {
        Text("L0G_T3RM1N4L", color = GundamNeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = GundamNeonGreen.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
        val scrollState = rememberLazyListState()
        LaunchedEffect(logs.size) { if (logs.isNotEmpty()) scrollState.animateScrollToItem(logs.size - 1) }
        LazyColumn(modifier = Modifier.fillMaxSize(), state = scrollState) {
            items(logs) { log -> Text("> $log", color = GundamNeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp) }
        }
    }
}

@Composable
fun SystemHeader(status: String, isDark: Boolean) {
    val textColor = if (isDark) GundamWhite else Color.Black
    Row(modifier = Modifier.fillMaxWidth().border(2.dp, GundamBlue, CutCornerShape(topEnd = 16.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("BLAKASUTHA A* PLANNER", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Text("v1.0 (Multi-Stage)", color = GundamLightGrey, fontSize = 10.sp)
        }
        Box(modifier = Modifier.size(width = 75.dp, height = 22.dp).background(if (status == "ONLINE") GundamNeonGreen else GundamRed, CutCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Text(status, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 10.sp)
        }
    }
}

@Composable
fun ConnectionPanel(ip: String, onIpChange: (String) -> Unit, port: String, onPortChange: (String) -> Unit, onConnect: (String, String) -> Unit, onDisconnect: () -> Unit, isDark: Boolean) {
    val textColor = if (isDark) GundamWhite else Color.Black
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = ip, onValueChange = onIpChange, label = { Text("IP", fontSize = 10.sp) }, modifier = Modifier.weight(0.65f), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor), shape = CutCornerShape(8.dp), singleLine = true)
            OutlinedTextField(value = port, onValueChange = onPortChange, label = { Text("PORT", fontSize = 10.sp) }, modifier = Modifier.weight(0.35f), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor), shape = CutCornerShape(8.dp), singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GundamButton("CONNECT", Color(0xFF4CAF50), { onConnect(ip, port) }, Modifier.weight(1f), compact = true)
            GundamButton("DISCONNECT", GundamLightGrey, onDisconnect, Modifier.weight(1f), compact = true)
        }
    }
}

@Composable
fun ActionButtons(team: String, r2: List<Int>, fake: Int?, onReset: () -> Unit, onSend: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GundamButton("RESET", GundamLightGrey, onReset, Modifier.weight(1f))
        GundamButton("KIRIM MISSION", GundamYellow, onSend, Modifier.weight(1f), textColor = Color.Black, enabled = team != "NONE" && r2.size == 4 && fake != null)
    }
}

@Composable
fun GundamButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, textColor: Color = Color.White, enabled: Boolean = true, compact: Boolean = false, border: androidx.compose.ui.unit.Dp = 0.dp) {
    Button(onClick = onClick, modifier = modifier.height(if (compact) 40.dp else 52.dp).border(border, GundamNeonGreen, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)), colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.3f)), shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp), enabled = enabled) {
        Text(text, color = if (enabled) textColor else textColor.copy(alpha = 0.5f), fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}
