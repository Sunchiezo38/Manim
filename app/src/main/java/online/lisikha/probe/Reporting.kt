package online.lisikha.probe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ReportSettings(
    val host:String="de-staging.lisikha-vpn.online",
    val chatId:String="",
    val intervalMinutes:Long=15,
    val enabled:Boolean=false,
    val attachExcel:Boolean=true
)

class ReportingConfigStore(private val context:Context){
    private val prefs=context.getSharedPreferences("reporting",Context.MODE_PRIVATE)
    private val secret=SecretStore(context)

    fun load()=ReportSettings(
        host=prefs.getString("host","de-staging.lisikha-vpn.online")?:"de-staging.lisikha-vpn.online",
        chatId=prefs.getString("chat_id","")?:"",
        intervalMinutes=prefs.getLong("interval",15).coerceAtLeast(15),
        enabled=prefs.getBoolean("enabled",false),
        attachExcel=prefs.getBoolean("attach_excel",true)
    )

    fun save(settings:ReportSettings, botToken:String?=null){
        prefs.edit()
            .putString("host",settings.host)
            .putString("chat_id",settings.chatId)
            .putLong("interval",settings.intervalMinutes.coerceAtLeast(15))
            .putBoolean("enabled",settings.enabled)
            .putBoolean("attach_excel",settings.attachExcel)
            .apply()
        if(botToken!=null && botToken.isNotBlank()) secret.put("telegram_token",botToken.trim())
    }

    fun botToken():String=secret.get("telegram_token")?:""
}

private class SecretStore(context:Context){
    private val prefs=context.getSharedPreferences("encrypted_secrets",Context.MODE_PRIVATE)
    private val alias="lisikha-probe-secret-key"

    private fun key():SecretKey{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return kg.generateKey()
    }

    fun put(name:String,value:String){
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key())
        val iv=Base64.encodeToString(c.iv,Base64.NO_WRAP)
        val data=Base64.encodeToString(c.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        prefs.edit().putString(name,"$iv:$data").apply()
    }

    fun get(name:String):String?=runCatching{
        val raw=prefs.getString(name,null)?:return null
        val p=raw.split(':',limit=2)
        if(p.size!=2)return null
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        val iv=Base64.decode(p[0],Base64.NO_WRAP)
        val data=Base64.decode(p[1],Base64.NO_WRAP)
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
        String(c.doFinal(data),Charsets.UTF_8)
    }.getOrNull()
}

class ProbeReportStore(private val context:Context){
    private val dataFile=File(context.filesDir,"lisikha_network_report.tsv")
    private val header=listOf("timestamp_utc","transport","network","host","system_vpn","test","status","required","latency_ms","detail")

    @Synchronized fun append(reports:List<ProbeReport>){
        if(!dataFile.exists()) dataFile.writeText(header.joinToString("\t")+"\n")
        val sb=StringBuilder()
        reports.forEach{r->
            r.results.forEach{x->
                sb.append(ts(r.createdAtMs)).append('\t')
                    .append(clean(r.transport.name)).append('\t')
                    .append(clean(r.networkLabel)).append('\t')
                    .append(clean(r.host)).append('\t')
                    .append(if(r.systemVpnDetected)"ON" else "OFF").append('\t')
                    .append(clean(x.name)).append('\t')
                    .append(if(x.ok)"PASS" else if(x.required)"FAIL" else "WARN").append('\t')
                    .append(x.required).append('\t')
                    .append(x.latencyMs?:"").append('\t')
                    .append(clean(x.detail)).append('\n')
            }
        }
        dataFile.appendText(sb.toString())
    }

    fun excelFile():File{
        if(!dataFile.exists()) dataFile.writeText(header.joinToString("\t")+"\n")
        val rows=dataFile.readLines().filter{it.isNotBlank()}.map{it.split('\t')}
        val out=File(context.cacheDir,"Lisikha_Network_Report.xlsx")
        writeXlsx(out,rows)
        return out
    }

    private fun ts(ms:Long):String=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(ms))
    private fun clean(s:String)=s.replace('\t',' ').replace('\n',' ').replace('\r',' ').take(500)

    private fun writeXlsx(file:File,rows:List<List<String>>){
        ZipOutputStream(file.outputStream().buffered()).use{z->
            fun entry(name:String,text:String){z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}
            entry("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            entry("_rels/.rels","""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Network tests" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels","""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val sheet=StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed{ri,row->
                sheet.append("<row r=\"").append(ri+1).append("\">")
                row.forEachIndexed{ci,v->
                    val ref=col(ci)+(ri+1)
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(xml(v)).append("</t></is></c>")
                }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            entry("xl/worksheets/sheet1.xml",sheet.toString())
        }
    }

    private fun col(i:Int):String{var n=i+1;var s="";while(n>0){n--;s=('A'.code+(n%26)).toChar()+s;n/=26};return s}
    private fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}

class TelegramReporter{
    suspend fun send(token:String,chatId:String,reports:List<ProbeReport>,excel:File?=null):String=withContext(Dispatchers.IO){
        require(token.isNotBlank() && chatId.isNotBlank()){ "Telegram bot token/chat ID not configured" }
        val summary=buildSummary(reports)
        sendMessage(token,chatId,summary)
        if(excel!=null) sendDocument(token,chatId,excel,"Lisikha network history")
        "Telegram report sent"
    }

    private fun buildSummary(reports:List<ProbeReport>):String{
        val sb=StringBuilder("Lisikha Probe report\n")
        reports.forEach{r->
            val fail=r.results.count{!it.ok && it.required}
            val warn=r.results.count{!it.ok && !it.required}
            val pass=r.results.count{it.ok && it.name!="System VPN"}
            val ip=r.results.firstOrNull{it.name=="Public IP"}?.detail?:"n/a"
            sb.append("\n${r.transport} / ${r.networkLabel}: PASS $pass, FAIL $fail, WARN $warn")
            sb.append("\nIP: $ip")
            if(r.systemVpnDetected)sb.append("\nSystem VPN: detected")
            r.results.filter{!it.ok}.forEach{x->sb.append("\n- ${if(x.required)"FAIL" else "WARN"} ${x.name}: ${x.detail.take(180)}")}
            sb.append('\n')
        }
        return sb.toString().take(3900)
    }

    private fun sendMessage(token:String,chatId:String,text:String){
        val url=URL("https://api.telegram.org/bot$token/sendMessage")
        val body="chat_id=${enc(chatId)}&text=${enc(text)}&disable_web_page_preview=true"
        val c=url.openConnection() as HttpURLConnection
        c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10000;c.readTimeout=10000
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use{it.write(body.toByteArray())}
        val code=c.responseCode
        if(code !in 200..299)error("Telegram sendMessage HTTP $code")
        c.disconnect()
    }

    private fun sendDocument(token:String,chatId:String,file:File,caption:String){
        val boundary="----Lisikha${System.currentTimeMillis()}"
        val c=URL("https://api.telegram.org/bot$token/sendDocument").openConnection() as HttpURLConnection
        c.requestMethod="POST";c.doOutput=true;c.connectTimeout=15000;c.readTimeout=20000
        c.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
        c.outputStream.buffered().use{o->
            fun text(name:String,value:String){o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())}
            text("chat_id",chatId);text("caption",caption)
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".toByteArray())
            file.inputStream().use{it.copyTo(o)}
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code=c.responseCode
        if(code !in 200..299)error("Telegram sendDocument HTTP $code")
        c.disconnect()
    }

    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
}
