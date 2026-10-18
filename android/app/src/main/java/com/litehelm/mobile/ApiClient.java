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
        try { u=new URI(clean); } catch(Exception e) { throw new Exception(Text.get(R.string.err_address_invalid)); }
        if (!"https".equals(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null || (u.getRawPath()!=null && !u.getRawPath().isEmpty()) || u.getPort()>65535 || u.getPort()==0)
            throw new Exception(Text.get(R.string.err_origin_https));
        token=token.trim();
        if(token.isEmpty()) throw new Exception(Text.get(R.string.err_token_required));
        if(hasControl(token)) throw new Exception(Text.get(R.string.err_control_chars));
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
            if(!current.getAsBoolean())throw new Exception(Text.get(R.string.err_cancelled_unsent));
            if(write) {
                byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                try(OutputStream out=c.getOutputStream()) { out.write(data); }
            }
            int code=c.getResponseCode();
            if(code>=300 && code<400) throw new Exception(Text.get(R.string.err_redirect));
            if(code==401||code==403) throw new Exception(Text.get(R.string.err_auth));
            if(code==404 && (path.equals("/api/v1/ports")||path.startsWith("/api/v1/timers")||path.equals("/api/v1/disks")||path.equals("/api/v1/disk-scan")||path.equals("/api/v1/jobs")))
                throw new Exception(Text.get(R.string.err_tool_unsupported));
            InputStream in=code>=200&&code<300 ? c.getInputStream():c.getErrorStream();
            if(in==null) throw new Exception(Text.get(R.string.err_http,code));
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            try(InputStream source=in) { byte[] chunk=new byte[8192]; int n; while((n=source.read(chunk))!=-1) { if(System.nanoTime()>deadline)throw new SocketTimeoutException();if(buffer.size()+n>2*1024*1024) throw new Exception(Text.get(R.string.err_too_large)); buffer.write(chunk,0,n); } }
            String text=buffer.toString(StandardCharsets.UTF_8.name());
            String type=c.getContentType();
            if((type!=null && type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) || text.trim().startsWith("<")) throw new Exception(Text.get(R.string.err_html));
            JSONObject result;
            try { result=new JSONObject(text); } catch(Exception e) { throw new Exception(Text.get(R.string.err_json)); }
            if(code<200 || code>=300) throw new Exception("HTTP "+code+" · "+redact(result.optString("error",Text.get(R.string.request_failed)),config));
            if(result.has("error")) throw new Exception(redact(result.optString("error"),config));
            if(path.endsWith("/logs") && result.optString("logs").getBytes(StandardCharsets.UTF_8).length>65536)throw new Exception(Text.get(R.string.err_logs_large));
            if(write && !path.equals("/api/v1/disk-scan") && !result.optBoolean("ok",false)) throw new Exception(Text.get(R.string.err_not_confirmed));
            return result;
        } catch(SocketTimeoutException e) { throw new Exception(write?Text.get(R.string.err_write_timeout):Text.get(R.string.err_timeout));
        } catch(SSLException e) { throw new Exception(Text.get(R.string.err_tls));
        } catch(IOException e) { throw new Exception(write?Text.get(R.string.err_write_io):Text.get(R.string.err_io));
        } finally { active=null; if(c!=null)c.disconnect(); }
    }
    static String redact(String s, Config c) {
        if(!c.token.isEmpty()) s=s.replace(c.token,Text.get(R.string.redacted));
        s=s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return s.length()>400?s.substring(0,400)+"…":s;
    }
}
