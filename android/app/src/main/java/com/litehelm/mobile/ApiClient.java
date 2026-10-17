package com.litehelm.mobile;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.SSLException;

/** One serialized transport, no redirects, default TLS trust, bounded bodies, no write retries. */
final class ApiClient {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final java.util.concurrent.atomic.AtomicBoolean MUTATING = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile HttpURLConnection active;
    private volatile boolean activeWrite;
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    ApiClient(){this(url->(HttpURLConnection)url.openConnection());}
    ApiClient(Connections connections){this.connections=connections;}
    static final class Config {
        final String origin, token;
        Config(String origin, String token) {
            this.origin=origin; this.token=token;
        }
        JSONObject json() throws Exception { return new JSONObject().put("origin",origin).put("token",token); }
        static Config parse(JSONObject j) { return new Config(j.optString("origin"),j.optString("token")); }
    }
    static Config validate(String address, String token) throws Exception {
        String clean=address.trim().replaceAll("/+$", "");
        URI u;
        try { u=new URI(clean); } catch(Exception e) { throw new Exception("服务器地址格式无效"); }
        if (!"https".equals(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null || (u.getRawPath()!=null && !u.getRawPath().isEmpty()) || u.getPort()>65535 || u.getPort()==0)
            throw new Exception("请输入完整 HTTPS origin，不要包含路径、查询或账号");
        token=token.trim();
        if(token.isEmpty()) throw new Exception("请填写访问令牌");
        if(hasControl(token)) throw new Exception("凭据中不能包含换行或控制字符");
        return new Config(clean,token);
    }
    private static boolean hasControl(String s) { for(int i=0;i<s.length();i++) if(s.charAt(i)<32 || s.charAt(i)==127) return true; return false; }
    void cancelRead() { HttpURLConnection c=active; if(c!=null && !activeWrite) c.disconnect(); }
    JSONObject request(Config config, String path, JSONObject body) throws Exception {return request(config,path,body,()->true);}
    JSONObject request(Config config, String path, JSONObject body,java.util.function.BooleanSupplier current) throws Exception {
        HttpURLConnection c=null;
        boolean write=body!=null;
        try {
            c=connections.open(new URL(config.origin+path));
            activeWrite=write&&!path.equals("/api/v1/disk-scan"); active=c;
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setUseCaches(false);
            c.setRequestProperty("Accept","application/json"); c.setRequestProperty("Authorization","Bearer "+config.token);
            // Register the cancellable connection before the final identity check, closing the preflight/cancel race.
            if(!current.getAsBoolean())throw new Exception("请求已取消，未发送");
            if(write) {
                byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                try(OutputStream out=c.getOutputStream()) { out.write(data); }
            }
            int code=c.getResponseCode();
            if(code>=300 && code<400) throw new Exception("服务器返回了重定向，请检查 HTTPS API 地址与反向代理配置");
            if(code==401||code==403) throw new Exception("认证失败，请检查访问令牌与 API 访问权限");
            if(code==404 && (path.equals("/api/v1/ports")||path.startsWith("/api/v1/timers")||path.equals("/api/v1/disks")||path.equals("/api/v1/disk-scan")))
                throw new Exception("此服务器尚不支持该工具，请更新 LiteHelm 服务端至 v0.2 或更新版本");
            InputStream in=code>=200&&code<300 ? c.getInputStream():c.getErrorStream();
            if(in==null) throw new Exception("服务器返回 HTTP "+code);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            try(InputStream source=in) { byte[] chunk=new byte[8192]; int n; while((n=source.read(chunk))!=-1) { if(System.nanoTime()>deadline)throw new SocketTimeoutException();if(buffer.size()+n>2*1024*1024) throw new Exception("服务器响应过大，已停止读取"); buffer.write(chunk,0,n); } }
            String text=buffer.toString(StandardCharsets.UTF_8.name());
            String type=c.getContentType();
            if((type!=null && type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) || text.trim().startsWith("<")) throw new Exception("服务器返回了网页，请检查 API 地址与反向代理配置");
            JSONObject result;
            try { result=new JSONObject(text); } catch(Exception e) { throw new Exception("服务器返回了无效 JSON，请检查 API 地址"); }
            if(code<200 || code>=300) throw new Exception("HTTP "+code+" · "+redact(result.optString("error","请求失败"),config));
            if(result.has("error")) throw new Exception(redact(result.optString("error"),config));
            if(path.endsWith("/logs") && result.optString("logs").getBytes(StandardCharsets.UTF_8).length>65536)throw new Exception("日志响应超过 64 KiB，请检查服务端限制");
            if(write && !path.equals("/api/v1/disk-scan") && !result.optBoolean("ok",false)) throw new Exception("服务器未确认操作成功，请刷新后检查");
            return result;
        } catch(SocketTimeoutException e) { throw new Exception(write?"结果未确认，请刷新检查；请勿直接重复提交":"请求超时，请检查服务器或网络");
        } catch(SSLException e) { throw new Exception("安全连接失败，请检查证书与 HTTPS 地址");
        } catch(IOException e) { throw new Exception(write?"连接中断，结果未确认，请刷新检查":"无法连接服务器，请检查地址、网络或服务状态");
        } finally { active=null; if(c!=null)c.disconnect(); }
    }
    static String redact(String s, Config c) {
        if(!c.token.isEmpty()) s=s.replace(c.token,"[已隐藏]");
        s=s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return s.length()>400?s.substring(0,400)+"…":s;
    }
}
