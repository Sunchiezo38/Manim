package online.lisikha.probe

enum class TargetTransport { ACTIVE, WIFI, CELLULAR }
data class ProbeResult(val name:String,val ok:Boolean,val latencyMs:Long?=null,val detail:String)
data class ProbeReport(val transport:TargetTransport,val networkLabel:String,val host:String,val results:List<ProbeResult>)
