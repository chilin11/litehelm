package com.litehelm.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;

/** Entire document is encrypted by CredentialStore, including names and favorites. */
final class ProfileBook {
    static final int FAVORITE_LIMIT=12;
    static final class Profile {
        final String id, name;
        final ApiClient.Config config;
        final ArrayList<String> favorites=new ArrayList<>();
        Profile(String id,String name,ApiClient.Config config) { this.id=id;this.name=name;this.config=config; }
        JSONObject json() throws Exception {
            return new JSONObject().put("id",id).put("name",name).put("origin",config.origin)
                .put("token",config.token).put("favorites",new JSONArray(favorites));
        }
    }
    final ArrayList<Profile> profiles=new ArrayList<>();
    String activeId="";
    Profile active() { return find(activeId); }
    Profile find(String id) { for(Profile p:profiles)if(p.id.equals(id))return p;return null; }
    static String newId() { return UUID.randomUUID().toString(); }
    JSONObject json() throws Exception {
        JSONArray list=new JSONArray();for(Profile p:profiles)list.put(p.json());
        return new JSONObject().put("version",2).put("active_id",activeId).put("profiles",list);
    }
    static ProfileBook parse(JSONObject document) throws Exception {
        ProfileBook book=new ProfileBook();
        if(!document.has("profiles")) {
            ApiClient.Config old=ApiClient.validate(document.getString("origin"),document.getString("token"));
            Profile p=new Profile(newId(),java.net.URI.create(old.origin).getHost(),old);
            book.profiles.add(p);book.activeId=p.id;return book;
        }
        JSONArray list=document.getJSONArray("profiles");
        for(int i=0;i<list.length();i++) {
            JSONObject j=list.getJSONObject(i);String id=j.getString("id"),name=j.getString("name");
            if(id.isEmpty()||name.trim().isEmpty()||book.find(id)!=null)throw new Exception(Text.get(R.string.err_profile_format));
            Profile p=new Profile(id,name,ApiClient.validate(j.getString("origin"),j.getString("token")));
            JSONArray favorites=j.optJSONArray("favorites");
            if(favorites!=null)for(int k=0;k<favorites.length()&&p.favorites.size()<FAVORITE_LIMIT;k++) {
                String unit=favorites.getString(k);if(!p.favorites.contains(unit))p.favorites.add(unit);
            }
            book.profiles.add(p);
        }
        book.activeId=document.optString("active_id");
        if(book.active()==null&&!book.profiles.isEmpty())book.activeId=book.profiles.get(0).id;
        return book;
    }
    ProfileBook copy() throws Exception { return parse(json()); }
}
