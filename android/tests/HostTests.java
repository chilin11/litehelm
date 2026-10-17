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
        System.out.println("PASS: "+checks+" checks (identity, stale requests, background/page/sheet polling, original logs, local matching)");
    }
}
