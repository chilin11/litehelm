package com.litehelm.mobile;

import java.nio.charset.StandardCharsets;

/** Pure script-job rules shared by the UI and host tests. */
final class JobOutput {
    static final int SCRIPT_LIMIT=64*1024, KEEP_CHARS=128*1024;
    static final int[] TIMEOUTS={300,1800,7200,86400};
    private JobOutput(){}
    static String normalize(String script){return script.replace("\r\n","\n");}
    static boolean fits(String script){String s=normalize(script);return !s.trim().isEmpty()&&s.indexOf('\0')<0&&s.getBytes(StandardCharsets.UTF_8).length<=SCRIPT_LIMIT;}
    static boolean finished(String state){return !state.equals("running")&&!state.equals("unknown");}
    static int timeoutIndex(int seconds){for(int i=0;i<TIMEOUTS.length;i++)if(TIMEOUTS[i]==seconds)return i;return 1;}
    /** Appends a polled chunk and keeps only the newest text, cutting at a line start when possible. */
    static String append(String current,String chunk,int limit){
        String all=current+chunk;if(all.length()<=limit)return all;
        int cut=all.length()-limit,line=all.indexOf('\n',cut);
        if(line>=0&&line<all.length()-1&&line-cut<1024)cut=line+1;
        else if(Character.isLowSurrogate(all.charAt(cut)))cut++;
        return all.substring(cut);
    }
}
