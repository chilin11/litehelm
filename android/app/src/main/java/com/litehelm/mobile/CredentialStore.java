package com.litehelm.mobile;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;

final class CredentialStore {
    private static final String ALIAS="litehelm.credentials.v1";
    private final Context context;
    CredentialStore(Context context) { this.context=context.getApplicationContext(); }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    ProfileBook load() throws Exception {
        String value=context.getSharedPreferences("vault",Context.MODE_PRIVATE).getString("encrypted",null);
        if(value==null) return null;
        JSONObject envelope=new JSONObject(value);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
        JSONObject document=new JSONObject(new String(c.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
        ProfileBook book=ProfileBook.parse(document);
        if(!document.has("profiles"))save(book); // Atomic replacement; legacy ciphertext survives a failed commit.
        return book;
    }
    void save(ProfileBook book) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
        byte[] data=c.doFinal(book.json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(data,Base64.NO_WRAP));
        if(!context.getSharedPreferences("vault",Context.MODE_PRIVATE).edit().putString("encrypted",envelope.toString()).commit()) throw new Exception("无法保存加密凭据，请检查设备存储");
    }
}
