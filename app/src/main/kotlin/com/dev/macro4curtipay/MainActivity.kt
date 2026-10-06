package com.dev.macro4curtipay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dev.macro4curtipay.ui.theme.Macro4CurtipayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Macro4CurtipayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isRunning by AutomationManager.isRunning.collectAsState()
    val statusMessage by AutomationManager.statusMessage.collectAsState()
    val completedCount by AutomationManager.completedCount.collectAsState()
    val skippedCount by AutomationManager.skippedCount.collectAsState()
    val serviceConnected by AutomationManager.serviceConnected.collectAsState()
    val manualMode by AutomationManager.manualMode.collectAsState()
    val taskKind by AutomationManager.currentTaskKind.collectAsState()
    val taskTitle by AutomationManager.currentTaskTitle.collectAsState()
    val logLines by AutomationManager.logLines.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Macro Curtipay Auto",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Curtir publicações e seguir perfis automaticamente",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (serviceConnected) {
                        "Acessibilidade: ATIVA"
                    } else {
                        "Acessibilidade: DESLIGADA"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (serviceConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Divider()
                Text(text = "Status: $statusMessage", fontSize = 13.sp)
                Text(
                    text = if (taskKind == TaskKind.UNKNOWN) {
                        "Tarefa atual: nenhuma"
                    } else {
                        "Tarefa atual: ${taskKind.label}${if (taskTitle.isEmpty()) "" else " — $taskTitle"}"
                    },
                    fontSize = 13.sp
                )
                Text(text = "Concluídas: $completedCount   |   Puladas: $skippedCount", fontSize = 13.sp)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Modo manual", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            text = "Ligado: o app só abre a tarefa e você faz a ação no Instagram. " +
                                "A Curtipay continua sendo confirmada automaticamente.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = manualMode,
                        onCheckedChange = { AutomationManager.setManualMode(it) }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = "Últimos eventos", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                if (logLines.isEmpty()) {
                    Text(text = "Nada por aqui ainda.", fontSize = 11.sp)
                } else {
                    logLines.takeLast(8).forEach { line ->
                        Text(text = line, fontSize = 10.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { AutomationManager.clearLog() }) {
                    Text(text = "Limpar eventos", fontSize = 11.sp)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (serviceConnected) "Serviço de acessibilidade (OK)" else "1. Ativar serviço de acessibilidade")
            }

            Button(
                onClick = {
                    if (Settings.canDrawOverlays(context)) {
                        context.startService(Intent(context, OverlayService::class.java))
                        AutomationManager.log("Sobreposição ligada")
                    } else {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("2. Botão flutuante (sobreposição)")
            }

            Button(
                onClick = { openCurtipay(context) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("3. Abrir Curtipay (tarefas do Instagram)")
            }

            Button(
                onClick = {
                    if (isRunning) {
                        AutomationManager.stopAutomation()
                    } else {
                        if (!serviceConnected) {
                            AutomationManager.log("Ative o serviço de acessibilidade primeiro")
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            AutomationManager.startAutomation()
                            openCurtipay(context)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Text(if (isRunning) "PARAR AUTOMAÇÃO" else "INICIAR AUTOMAÇÃO")
            }

            Text(
                text = "Deixe a Curtipay aberta na página de tarefas e o Instagram com a sessão " +
                    "iniciada. O app identifica se a tarefa é curtir ou seguir, abre o Instagram, " +
                    "faz a ação, volta e confirma a tarefa sozinho.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openCurtipay(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(AutomationManager.CURTIPAY_URL))
        )
    } catch (t: Throwable) {
        AutomationManager.log("Não consegui abrir o navegador: ${t.message}")
    }
}
