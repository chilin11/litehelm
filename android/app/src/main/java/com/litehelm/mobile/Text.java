package com.litehelm.mobile;

import android.content.Context;
import android.content.res.Resources;

/** Localized text for code paths without a Context, such as background request errors. */
final class Text {
    private static volatile Resources resources;
    private Text() {}
    static void use(Context context) { resources=context.getResources(); }
    private static Resources resources() {
        Resources r=resources;
        if(r==null) throw new IllegalStateException("Text resources are not initialized");
        return r;
    }
    static String get(int id,Object... args) { return args.length==0?resources().getString(id):resources().getString(id,args); }
    static String plural(int id,int count,Object... args) { return resources().getQuantityString(id,count,args); }
}
