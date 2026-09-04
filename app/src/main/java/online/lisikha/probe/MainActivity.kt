package online.lisikha.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity(){
    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        val p=NetworkProbeEngine(this)
        val s=SshCommander()
        setContent{App(p,s)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(p:NetworkProbeEngine,s:SshCommander){
    val context=LocalContext.current
    val cfgStore=remember{ReportingConfigStore(context)}
    val reportStore=remember{ProbeReportStore(context)}
    val saved=remember{cfgStore.load()}

    var vh by remember{mutableStateOf(saved.host)}
    var sh by remember{mutableStateOf("13.143.64.36")}
    var u by remember{mutableStateOf("root")}
    var pw by remember{mutableStateOf("")}
    var t by remember{mutableStateOf(TargetTransport.ACTIVE)}
    var reports by remember{mutableStateOf<List<ProbeReport>>(emptyList())}
    var q by remember{mutableStateOf("статус xray")}
    var out by remember{mutableStateOf("Ready")}
    var busy by remember{mutableStateOf(false)}
    var reportStatus by remember{mutableStateOf("Reporting ready")}

    var botToken by remember{mutableStateOf("")}
    var chatId by remember{mutableStateOf(saved.chatId)}
    var interval by remember{mutableStateOf(saved.intervalMinutes.toString())}
    var autoReports by remember{mutableStateOf(saved.enabled)}
    var attachExcel by remember{mutableStateOf(saved.attachExcel)}

    val sc=rememberCoroutineScope()

    fun persistAndSend(newReports:List<ProbeReport>){
        reports=newReports
        reportStore.append(newReports)
        sc.launch{
            val cfg=cfgStore.load()
            val token=cfgStore.botToken()
            reportStatus=if(token.isNotBlank() && cfg.chatId.isNotBlank()){
                runCatching{
                    TelegramReporter().send(token,cfg.chatId,newReports,if(cfg.attachExcel)reportStore.excelFile() else null)
                }.getOrElse{"Telegram error: ${it.message}"}
            }else "Saved locally. Configure Telegram to send reports."
        }
    }

    MaterialTheme{
        Scaffold(topBar={TopAppBar(title={Text("Lisikha Probe + AI Console")})}){pad->
            LazyColumn(
                Modifier.padding(pad).padding(12.dp).fillMaxSize(),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ){
                item{Text("Network tests",style=MaterialTheme.typography.titleLarge)}
                item{OutlinedTextField(vh,{vh=it.trim()},Modifier.fillMaxWidth(),label={Text("VPN host")})}
                item{
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        TargetTransport.entries.forEach{x->
                            FilterChip(selected=t==x,onClick={t=x},label={Text(x.name)})
                        }
                    }
                }
                item{
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(enabled=!busy,onClick={
                            busy=true
                            sc.launch{
                                val rr=listOf(p.run(vh,t))
                                persistAndSend(rr)
                                busy=false
                            }
                        }){Text("RUN TEST")}
                        OutlinedButton(enabled=!busy,onClick={
                            busy=true
                            sc.launch{
                                val rr=p.runMatrix(vh)
                                persistAndSend(rr)
                                busy=false
                            }
                        }){Text("RUN MATRIX")}
                    }
                }

                reports.forEach{rep->
                    item{
                        Column{
                            Text("${rep.networkLabel} / ${rep.transport}",style=MaterialTheme.typography.titleMedium)
                            if(rep.systemVpnDetected){
                                Text("Third-party/system VPN detected — disable it for a clean baseline.")
                            }
                        }
                    }
                    items(rep.results){z->
                        val state=when{
                            z.ok->"PASS"
                            z.required->"FAIL"
                            else->"WARN"
                        }
                        Card(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(10.dp)){
                                Text("$state — ${z.name}")
                                z.latencyMs?.let{Text("$it ms")}
                                Text(z.detail)
                            }
                        }
                    }
                }

                item{HorizontalDivider();Text("Telegram + Excel reporting",style=MaterialTheme.typography.titleLarge)}
                item{Text("Every manual test is written to an Excel history file. Telegram can receive the summary + XLSX. Background Android reports run at 15 minutes or slower.")}
                item{OutlinedTextField(chatId,{chatId=it.trim()},Modifier.fillMaxWidth(),label={Text("Telegram chat ID")})}
                item{OutlinedTextField(botToken,{botToken=it.trim()},Modifier.fillMaxWidth(),label={Text("Bot token (leave blank to keep saved token)")},visualTransformation=PasswordVisualTransformation())}
                item{OutlinedTextField(interval,{interval=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text("Background interval, minutes (min 15)")})}
                item{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Attach Excel (.xlsx)")
                        Switch(checked=attachExcel,onCheckedChange={attachExcel=it})
                    }
                }
                item{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Automatic reports")
                        Switch(checked=autoReports,onCheckedChange={autoReports=it})
                    }
                }
                item{
                    Button(onClick={
                        val mins=(interval.toLongOrNull()?:15).coerceAtLeast(15)
                        val settings=ReportSettings(vh,chatId,mins,autoReports,attachExcel)
                        cfgStore.save(settings,botToken.ifBlank{null})
                        botToken=""
                        if(autoReports)ProbeWorker.schedule(context,mins) else ProbeWorker.cancel(context)
                        reportStatus=if(autoReports)"Saved. Automatic reports scheduled every $mins min." else "Saved. Automatic reports disabled."
                    }){Text("SAVE REPORTING SETTINGS")}
                }
                item{
                    OutlinedButton(enabled=!busy,onClick={
                        busy=true
                        sc.launch{
                            val cfg=cfgStore.load();val token=cfgStore.botToken()
                            reportStatus=if(token.isBlank()||cfg.chatId.isBlank())"Configure bot token + chat ID first" else runCatching{
                                TelegramReporter().send(token,cfg.chatId,reports,if(cfg.attachExcel)reportStore.excelFile() else null)
                            }.getOrElse{"Telegram error: ${it.message}"}
                            busy=false
                        }
                    }){Text("SEND EXCEL REPORT NOW")}
                }
                item{Card(Modifier.fillMaxWidth()){Text(reportStatus,Modifier.padding(12.dp))}}

                item{HorizontalDivider();Text("Server direct control",style=MaterialTheme.typography.titleLarge)}
                item{OutlinedTextField(sh,{sh=it.trim()},Modifier.fillMaxWidth(),label={Text("SSH server")})}
                item{OutlinedTextField(u,{u=it.trim()},Modifier.fillMaxWidth(),label={Text("SSH user")})}
                item{OutlinedTextField(pw,{pw=it},Modifier.fillMaxWidth(),label={Text("SSH password (not saved)")},visualTransformation=PasswordVisualTransformation())}
                item{OutlinedTextField(q,{q=it},Modifier.fillMaxWidth(),label={Text("AI command")})}
                item{
                    Button(enabled=!busy&&pw.isNotBlank(),onClick={
                        val plan=SafeAiPlanner.plan(q)
                        if(plan==null)out="Try: статус xray / логи xray / перезапусти xray / память / диск / сеть"
                        else{
                            busy=true
                            sc.launch{
                                out=runCatching{
                                    val net=p.resolveNetwork(t)
                                    "AI plan: ${plan.label}\n\n"+s.exec(net,sh,22,u,pw,plan.command)
                                }.getOrElse{"ERROR: ${it.message}"}
                                busy=false
                            }
                        }
                    }){Text("SEND AI COMMAND TO SERVER")}
                }
                item{Card(Modifier.fillMaxWidth()){Text(out,Modifier.padding(12.dp))}}
            }
        }
    }
}
