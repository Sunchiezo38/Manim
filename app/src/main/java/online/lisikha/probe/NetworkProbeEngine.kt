package online.lisikha.probe

import android.content.Context
import android.net.*
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume

class NetworkProbeEngine(private val context:Context){
    private val cm=context.getSystemService(ConnectivityManager::class.java)

    suspend fun resolveNetwork(t:TargetTransport):Network?=when(t){
        TargetTransport.ACTIVE->cm.activeNetwork
        TargetTransport.WIFI->requestNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        TargetTransport.CELLULAR->requestNetwork(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun systemVpnDetected():Boolean = cm.allNetworks.any { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
    }

    suspend fun run(host:String,t:TargetTransport):ProbeReport=withContext(Dispatchers.IO){
        val vpnOn=systemVpnDetected()
        val n=resolveNetwork(t)?:return@withContext ProbeReport(
            t,"Unavailable",host,
            listOf(ProbeResult("Network",false,detail="Requested network is unavailable")),
            systemVpnDetected=vpnOn
        )
        val c=cm.getNetworkCapabilities(n)
        val label=when{
            c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"Wi-Fi"
            c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->cellularLabel()
            c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true->"Ethernet"
            c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true->"VPN"
            else->"Other"
        }
        val o=mutableListOf<ProbeResult>()
        o+=ProbeResult("System VPN",true,detail=if(vpnOn) "DETECTED — disable third-party VPN for a clean baseline" else "Not detected",required=false)
        o+=timed("DNS"){n.getAllByName(host).joinToString{it.hostAddress?:"?"}}
        o+=timed("TCP 8443"){tcp(n,host,8443)}
        o+=timed("TLS 8443"){tls(n,host,8443)}
        o+=timed("TCP 443 (optional)",required=false){tcp(n,host,443)}
        o+=timed("HTTPS baseline"){https(n,"https://www.google.com/generate_204")}
        o+=timed("Public IP"){https(n,"https://api.ipify.org")}
        ProbeReport(t,label,host,o,systemVpnDetected=vpnOn)
    }

    suspend fun runMatrix(host:String):List<ProbeReport>{
        val reports=mutableListOf<ProbeReport>()
        for(t in listOf(TargetTransport.ACTIVE,TargetTransport.WIFI,TargetTransport.CELLULAR)) reports+=run(host,t)
        return reports
    }

    private fun cellularLabel():String{
        val tm=context.getSystemService(TelephonyManager::class.java)
        val op=runCatching{tm.networkOperatorName}.getOrDefault("").trim()
        return if(op.isBlank()) "Mobile" else "Mobile / $op"
    }

    private suspend fun requestNetwork(tr:Int):Network?=suspendCancellableCoroutine{cont->
        val req=NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addTransportType(tr).build()
        val cb=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(n:Network){runCatching{cm.unregisterNetworkCallback(this)};if(cont.isActive)cont.resume(n)}
            override fun onUnavailable(){runCatching{cm.unregisterNetworkCallback(this)};if(cont.isActive)cont.resume(null)}
        }
        cm.requestNetwork(req,cb,12000)
        cont.invokeOnCancellation{runCatching{cm.unregisterNetworkCallback(cb)}}
    }

    private fun tcp(n:Network,h:String,p:Int):String{
        n.socketFactory.createSocket().use{s->s.connect(InetSocketAddress(h,p),5000);return "Connected ${s.inetAddress.hostAddress}:$p"}
    }

    private fun tls(n:Network,h:String,p:Int):String{
        val raw=n.socketFactory.createSocket()
        raw.connect(InetSocketAddress(h,p),5000)
        raw.soTimeout=5000
        raw.use{
            val ssl=(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,h,p,false) as SSLSocket
            ssl.use{s->
                s.startHandshake()
                val ses=s.session
                require(HttpsURLConnection.getDefaultHostnameVerifier().verify(h,ses)){"Hostname verification failed"}
                return "${ses.protocol}; ${ses.cipherSuite}"
            }
        }
    }

    private fun https(n:Network,u:String):String{
        val c=n.openConnection(URL(u)) as HttpsURLConnection
        c.connectTimeout=6000;c.readTimeout=6000;c.requestMethod="GET"
        return try{
            val code=c.responseCode
            val body=runCatching{c.inputStream.bufferedReader().use{it.readText()}}.getOrDefault("")
            if(code !in 200..399)error("HTTP $code")
            if(body.isBlank())"HTTP $code" else body.trim().take(160)
        }finally{c.disconnect()}
    }

    private inline fun timed(name:String,required:Boolean=true,b:()->String):ProbeResult{
        val s=System.nanoTime()
        return try{
            val detail=b()
            ProbeResult(name,true,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-s),detail,required)
        }catch(t:Throwable){
            ProbeResult(name,false,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-s),"${t.javaClass.simpleName}: ${t.message?:"error"}",required)
        }
    }
}
