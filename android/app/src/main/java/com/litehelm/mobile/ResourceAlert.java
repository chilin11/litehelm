package com.litehelm.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads the server-computed {@code resource_monitor} report from {@code GET /api/v1/system}.
 * The phone never compares usage with a threshold: alerts, the threshold and the monitor state
 * all come from the server. Anything other than a fresh, well-formed {@code state=ok} report is
 * surfaced as "monitoring unavailable" instead of being presented as healthy.
 * No background sampling, scheduling or notifications.
 */
final class ResourceAlert {
    /** Server sample age, or time without a successful refresh on the phone, that makes a report stale. */
    static final double MAX_AGE_SECONDS=15;
    static final int OK=0, ALERT=1, OFFLINE=2, MISSING=3, WARMING=4, ERROR=5, EXPIRED=6, INVALID=7;

    static final class Item {
        final String resource;final double percent;
        Item(String resource,double percent){this.resource=resource;this.percent=percent;}
    }

    static final class Report {
        final int status;final double threshold;final long sampledAt;final List<Item> items;
        Report(int status,double threshold,long sampledAt,List<Item> items){this.status=status;this.threshold=threshold;this.sampledAt=sampledAt;this.items=items;}
        /** Only these two states describe current server monitoring; everything else means unknown. */
        boolean current(){return status==OK||status==ALERT;}
    }

    private ResourceAlert() {}

    /**
     * Classifies already-extracted report fields. {@code alerts} is null when the array or any entry
     * is malformed. {@code sampleAge} is server response time minus {@code sampled_at};
     * {@code sinceRefresh} is how long ago the phone received that response.
     */
    static Report classify(boolean responseFresh,boolean present,String state,long sampledAt,double threshold,List<Item> alerts,double sampleAge,double sinceRefresh) {
        List<Item> none=Collections.emptyList();
        if(!responseFresh)return new Report(OFFLINE,Double.NaN,0,none);
        if(!present)return new Report(MISSING,Double.NaN,0,none);
        boolean thresholdValid=Double.isFinite(threshold)&&threshold>0&&threshold<=100;
        if(state==null||!thresholdValid||alerts==null)return new Report(INVALID,Double.NaN,0,none);
        double t=threshold;
        switch(state){
            case "warming_up":return new Report(WARMING,t,sampledAt,none);
            case "error":return new Report(ERROR,t,sampledAt,none);
            case "ok":break;
            default:return new Report(INVALID,Double.NaN,0,none);
        }
        if(sampledAt<=0||!Double.isFinite(sampleAge)||!Double.isFinite(sinceRefresh))return new Report(INVALID,Double.NaN,0,none);
        if(sampleAge>=MAX_AGE_SECONDS||sampleAge<=-MAX_AGE_SECONDS||sinceRefresh>=MAX_AGE_SECONDS||sinceRefresh<0)return new Report(EXPIRED,t,sampledAt,none);
        return new Report(alerts.isEmpty()?OK:ALERT,t,sampledAt,Collections.unmodifiableList(alerts));
    }

    /**
     * Extracts {@code resource_monitor} from a /system response and classifies it.
     * @param nowMillis phone wall clock used for {@code sinceRefresh} and as a fallback reference
     * @param receivedMillis phone wall clock when the response arrived
     */
    static Report read(JSONObject system,boolean responseFresh,long receivedMillis,long nowMillis) {
        double sinceRefresh=(nowMillis-receivedMillis)/1000.0;
        Object raw=system==null?null:system.opt("resource_monitor");
        if(!(raw instanceof JSONObject))return classify(responseFresh,false,null,0,Double.NaN,null,Double.NaN,sinceRefresh);
        JSONObject m=(JSONObject)raw;
        Object state=m.opt("state"), sampled=m.opt("sampled_at"), threshold=m.opt("threshold_percent"), stamp=system.opt("timestamp");
        long sampledAt=sampled instanceof Number?((Number)sampled).longValue():0;
        // Prefer the server's own response time so phone clock skew cannot hide or fake staleness.
        double reference=stamp instanceof Number&&((Number)stamp).longValue()>0?((Number)stamp).doubleValue():receivedMillis/1000.0;
        return classify(responseFresh,true,state instanceof String?(String)state:null,sampledAt,
            threshold instanceof Number?((Number)threshold).doubleValue():Double.NaN,
            items(m.opt("alerts")),reference-sampledAt,sinceRefresh);
    }

    private static List<Item> items(Object raw) {
        if(!(raw instanceof JSONArray))return null;
        JSONArray array=(JSONArray)raw;List<Item> out=new ArrayList<>();
        for(int i=0;i<array.length();i++){
            Object entry=array.opt(i);if(!(entry instanceof JSONObject))return null;
            Object resource=((JSONObject)entry).opt("resource"), percent=((JSONObject)entry).opt("percent");
            if(!(resource instanceof String)||((String)resource).isEmpty()||!(percent instanceof Number))return null;
            double value=((Number)percent).doubleValue();if(!Double.isFinite(value)||value<0)return null;
            out.add(new Item((String)resource,value));
        }
        return out;
    }
}
