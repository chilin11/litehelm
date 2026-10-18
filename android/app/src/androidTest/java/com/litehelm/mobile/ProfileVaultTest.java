package com.litehelm.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/** Run only on an explicitly selected test device; never clears the app's actual vault. */
public final class ProfileVaultTest extends Instrumentation {
    private Context context;
    private SharedPreferences preferences;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{setUp();testLegacyCiphertextMigrationAndIsolation();testCapabilityAndScanEnvelopes();testRequestBodiesAndErrors();testLocaleResolution();result.putString("stream","PASS: encrypted migration, profile isolation, ciphertext integrity, tool envelopes, request bodies, 404, no retry, locale resolution\n");finish(Activity.RESULT_OK,result);}
        catch(Throwable error){result.putString("stream","FAIL: "+error+"\n");finish(Activity.RESULT_CANCELED,result);}
        finally{if(preferences!=null)preferences.edit().clear().commit();}
    }
    private static void assertTrue(boolean value){if(!value)throw new AssertionError("Expected true");}
    private static void assertFalse(boolean value){assertTrue(!value);}
    private static void assertEquals(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Values differ");}
    private static void fail(String message){throw new AssertionError(message);}
    private void setUp() {
        context=new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("v02-test-"+name,mode);}
        };
        Text.use(getTargetContext());preferences=context.getSharedPreferences("vault",Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
    }
    public void testLegacyCiphertextMigrationAndIsolation() throws Exception {
        CredentialStore store=new CredentialStore(context);store.save(new ProfileBook());
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        SecretKey key=(SecretKey)keys.getKey("litehelm.credentials.v1",null);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        String token="legacy-test-token";
        JSONObject old=new JSONObject().put("origin","https://first.example").put("token",token);
        JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .put("data",Base64.encodeToString(cipher.doFinal(old.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
        assertTrue(preferences.edit().putString("encrypted",envelope.toString()).commit());
        ProfileBook book=store.load();String id=book.activeId;
        assertEquals(token,book.active().config.token);assertEquals("https://first.example",book.active().config.origin);
        assertEquals(id,store.load().activeId);assertFalse(preferences.getString("encrypted","").contains(token));
        book.active().favorites.add("same.service");
        ProfileBook.Profile second=new ProfileBook.Profile(ProfileBook.newId(),"Second",new ApiClient.Config("https://second.example","second-token"));
        book.profiles.add(second);book.activeId=second.id;store.save(book);
        ProfileBook restored=store.load();assertEquals(second.id,restored.activeId);assertTrue(restored.active().favorites.isEmpty());
        assertEquals("same.service",restored.find(id).favorites.get(0));
        String encrypted=preferences.getString("encrypted","");assertFalse(encrypted.contains("second-token"));assertFalse(encrypted.contains("same.service"));
        // Corruption must fail closed, never return another host or silently discard the vault.
        JSONObject corrupt=new JSONObject(encrypted);corrupt.put("data",Base64.encodeToString(new byte[32],Base64.NO_WRAP));preferences.edit().putString("encrypted",corrupt.toString()).commit();
        try{store.load();fail("Tampered ciphertext accepted");}catch(javax.crypto.AEADBadTagException expected){}
    }
    public void testCapabilityAndScanEnvelopes() throws Exception {
        ToolData.validate("/api/v1/ports",new JSONObject("{\"supported\":false,\"ports\":[],\"message\":\"unavailable\"}"));
        ToolData.validate("/api/v1/timers",new JSONObject("{\"supported\":false,\"timers\":[]}"));
        JSONObject scan=new JSONObject("{\"path\":\"/var/log\",\"bytes\":1,\"files\":1,\"entries\":[],\"partial\":true,\"reason\":\"permission limit\",\"elapsed_ms\":3,\"metric\":\"logical_bytes\"}");
        ToolData.validate("/api/v1/disk-scan",scan);
        scan.put("metric","physical");try{ToolData.validate("/api/v1/disk-scan",scan);fail("Unknown scan metric accepted");}catch(Exception expected){}
    }
    public void testLocaleResolution() {
        // Region-only Chinese locales must pick the matching script; unsupported languages fall back to English.
        String[][] cases={{"zh-CN","轻舵 LiteHelm"},{"zh-SG","轻舵 LiteHelm"},{"zh-Hans","轻舵 LiteHelm"},{"zh-TW","輕舵 LiteHelm"},{"zh-HK","輕舵 LiteHelm"},{"zh-Hant","輕舵 LiteHelm"},{"en-US","LiteHelm"},{"fr-FR","LiteHelm"}};
        for(String[] c:cases){android.content.res.Configuration config=new android.content.res.Configuration(getTargetContext().getResources().getConfiguration());config.setLocale(java.util.Locale.forLanguageTag(c[0]));
            android.content.res.Resources r=getTargetContext().createConfigurationContext(config).getResources();
            if(!c[1].equals(r.getString(R.string.app_name)))throw new AssertionError("Locale "+c[0]+" resolved to "+r.getString(R.string.app_name));
            r.getString(R.string.disk_detail,"a","b","c","d","e","f","g");r.getQuantityString(R.plurals.files_count,2,2);}
    }
    private static final class FakeConnection extends java.net.HttpURLConnection {
        final java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();String response="{\"ok\":true}";int code=200;
        FakeConnection(java.net.URL url){super(url);}
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public java.io.OutputStream getOutputStream(){return sent;}
        @Override public int getResponseCode(){return code;}
        @Override public String getContentType(){return "application/json";}
        @Override public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
        @Override public java.io.InputStream getErrorStream(){return getInputStream();}
    }
    public void testRequestBodiesAndErrors() throws Exception {
        final FakeConnection[] last={null};final int[] calls={0};final String[] response={"{\"ok\":true}"};final int[] code={200};
        ApiClient client=new ApiClient(url->{calls[0]++;last[0]=new FakeConnection(url);last[0].response=response[0];last[0].code=code[0];return last[0];});
        ApiClient.Config config=new ApiClient.Config("https://host.example","secret-test-token");
        client.request(config,"/api/v1/timers/backup.timer/run",new JSONObject().put("confirm","backup.timer"));
        assertEquals("POST",last[0].getRequestMethod());assertEquals("backup.timer",new JSONObject(last[0].sent.toString("UTF-8")).getString("confirm"));assertEquals("Bearer secret-test-token",last[0].getRequestProperty("Authorization"));
        response[0]="{\"path\":\"/var/log\",\"entries\":[],\"partial\":true}";
        client.request(config,"/api/v1/disk-scan",new JSONObject().put("path","/var/log"));
        assertEquals("/var/log",new JSONObject(last[0].sent.toString("UTF-8")).getString("path"));
        code[0]=404;int before=calls[0];
        try{client.request(config,"/api/v1/ports",null);fail("404 accepted");}catch(Exception e){assertEquals(Text.get(R.string.err_tool_unsupported),e.getMessage());}
        assertEquals(before+1,calls[0]);
        code[0]=429;response[0]="{\"error\":\"cooldown secret-test-token\"}";before=calls[0];
        try{client.request(config,"/api/v1/disk-scan",new JSONObject().put("path","/var/log"));fail("429 accepted");}catch(Exception e){assertTrue(e.getMessage().contains("429"));assertFalse(e.getMessage().contains(config.token));}
        assertEquals(before+1,calls[0]);
        char[] large=new char[65537];java.util.Arrays.fill(large,'a');code[0]=200;response[0]="{\"logs\":\""+new String(large)+"\"}";
        try{client.request(config,"/api/v1/services/test.service/logs",null);fail("Oversized logs accepted");}catch(Exception e){assertTrue(e.getMessage().contains("64 KiB"));}
    }
}
