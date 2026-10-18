package com.litehelm.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

/** Validate capability envelopes before presenting any action. */
final class ToolData {
    static void validate(String path,JSONObject data) throws Exception {
        if(path.equals("/api/v1/disk-scan")) {
            data.getString("path");data.getLong("bytes");data.getLong("files");data.getJSONArray("entries");data.getBoolean("partial");data.getString("reason");data.getLong("elapsed_ms");
            if(!"logical_bytes".equals(data.getString("metric")))throw new Exception(Text.get(R.string.err_scan_metric));
        }else if(path.equals("/api/v1/disks")) {
            data.getJSONArray("disks");data.getJSONArray("scan_roots");data.getBoolean("truncated");
        }else if(path.equals("/api/v1/jobs")) {
            if(data.getBoolean("supported")){data.getString("runner");data.getString("user");}
            JSONArray jobs=data.getJSONArray("jobs");for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);job.getString("id");job.getString("state");}
        }else if(path.startsWith("/api/v1/jobs/")) {
            JSONObject job=data.getJSONObject("job");job.getString("id");job.getString("state");
            data.getString("output");long offset=data.getLong("offset"),next=data.getLong("next_offset"),size=data.getLong("size");
            if(offset<0||next<offset||size<next)throw new Exception(Text.get(R.string.err_tool_format));
        }else {
            boolean supported=data.getBoolean("supported");JSONArray entries=data.getJSONArray(path.endsWith("ports")?"ports":"timers");
            if(supported)data.getBoolean("truncated");
            if(path.endsWith("timers"))for(int i=0;i<entries.length();i++) {
                JSONObject timer=entries.getJSONObject(i);timer.getString("name");timer.getString("unit");timer.getBoolean("can_run");
            }
        }
    }
    static String available(String value){return value.isEmpty()?Text.get(R.string.unavailable):value;}
    static String searchText(String kind,JSONObject item){
        if(kind.equals("ports"))return item.optString("address")+" "+item.optString("process")+" "+item.optString("service")+" "+item.optString("pid");
        if(kind.equals("timers"))return item.optString("name")+" "+item.optString("description")+" "+item.optString("unit");
        return item.optString("mountpoint")+" "+item.optString("device")+" "+item.optString("filesystem");
    }
}
