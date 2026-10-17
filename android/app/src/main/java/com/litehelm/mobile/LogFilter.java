package com.litehelm.mobile;

import java.util.Locale;

final class LogFilter {
    static final class Result {
        final String visible;final int matches,lines;
        Result(String visible,int matches,int lines){this.visible=visible;this.matches=matches;this.lines=lines;}
    }
    static Result filter(String original,String query) {
        if(query.isEmpty())return new Result(original,0,original.isEmpty()?0:original.split("\n",-1).length);
        String needle=query.toLowerCase(Locale.ROOT);StringBuilder visible=new StringBuilder();int matches=0,lines=0;
        for(String line:original.split("\n",-1)) {
            String folded=line.toLowerCase(Locale.ROOT);int at=0,count=0;
            while((at=folded.indexOf(needle,at))>=0){count++;at+=needle.length();}
            if(count>0){if(lines++>0)visible.append('\n');visible.append(line);matches+=count;}
        }
        return new Result(visible.toString(),matches,lines);
    }
}
