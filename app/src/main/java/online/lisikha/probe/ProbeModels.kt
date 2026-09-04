package online.lisikha.probe

enum class TargetTransport { ACTIVE, WIFI, CELLULAR }
data class ProbeResult(
    val name:String,
    val ok:Boolean,
    val latencyMs:Long?=null,
    val detail:String,
    val required:Boolean=true
)
data class ProbeReport(
    val transport:TargetTransport,
    val networkLabel:String,
    val host:String,
    val results:List<ProbeResult>,
    val systemVpnDetected:Boolean=false,
    val createdAtMs:Long=System.currentTimeMillis()
)
