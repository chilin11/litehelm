package com.litehelm.mobile;

/** Dependency-free executable tests for shared production policy and log filtering. */
public final class HostTests {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    public static void main(String[] args) {
        Object hostA=new Object(),hostB=new Object();
        check(RequestPolicy.current(true,3,3,hostA,hostA,false,0,0));
        check(!RequestPolicy.current(false,3,3,hostA,hostA,false,0,0));
        check(!RequestPolicy.current(true,4,3,hostA,hostA,false,0,0));
        check(!RequestPolicy.current(true,3,3,hostB,hostA,false,0,0));
        check(!RequestPolicy.current(true,3,3,hostA,hostA,true,5,4));
        check(RequestPolicy.current(true,3,3,hostA,hostA,false,5,4));
        check(RequestPolicy.current(true,3,3,hostA,hostA,true,4,4));
        for(int page=0;page<4;page++)for(int mask=0;mask<32;mask++) {
            boolean fg=(mask&1)!=0,configured=(mask&2)!=0,busy=(mask&4)!=0,mutating=(mask&8)!=0,sheet=(mask&16)!=0;
            boolean allowed=RequestPolicy.poll(fg,page,configured,busy,mutating,sheet);
            check(allowed==(page==0&&fg&&configured&&!busy&&!mutating&&!sheet));
        }
        String original="Error ERROR\nnormal line\n错误: error\n";
        LogFilter.Result all=LogFilter.filter(original,"");check(all.visible.equals(original));check(all.matches==0);
        LogFilter.Result filtered=LogFilter.filter(original,"error");check(filtered.matches==3);check(filtered.lines==2);check(filtered.visible.equals("Error ERROR\n错误: error"));
        check(LogFilter.filter(original,"missing").visible.isEmpty());
        check(LogFilter.filter("aaaa","aa").matches==2);
        check(LogFilter.filter("","").lines==0);
        check(LogFilter.filter("错误\n正常","错误").matches==1);
        check(JobOutput.normalize("a\r\nb\r\n").equals("a\nb\n"));check(JobOutput.fits("echo 中文"));check(!JobOutput.fits("  \n "));check(!JobOutput.fits("a\0b"));
        check(JobOutput.fits("x".repeat(JobOutput.SCRIPT_LIMIT)));check(!JobOutput.fits("x".repeat(JobOutput.SCRIPT_LIMIT+1)));check(!JobOutput.fits("中".repeat(JobOutput.SCRIPT_LIMIT/3+1)));
        for(String s:new String[]{"succeeded","failed","cancelled","timeout","killed","lost"})check(JobOutput.finished(s));check(!JobOutput.finished("running"));check(!JobOutput.finished("unknown"));
        check(JobOutput.timeoutIndex(300)==0);check(JobOutput.timeoutIndex(86400)==3);check(JobOutput.timeoutIndex(42)==1);
        check(JobOutput.append("ab","cd",10).equals("abcd"));check(JobOutput.append("line1\nline2\n","line3\n",14).equals("line2\nline3\n"));
        check(JobOutput.append("a\uD83D\uDE00","b",2).equals("b"));check(JobOutput.append("xx\uD83D\uDE00yy","",3).equals("yy"));
        System.out.println("PASS: "+checks+" checks (identity, stale requests, background/page/sheet polling, original logs, local matching, script jobs)");
    }
}
