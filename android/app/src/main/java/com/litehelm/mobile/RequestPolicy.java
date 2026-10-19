package com.litehelm.mobile;

/** Shared lifecycle/identity rules for both preflight and UI delivery. */
final class RequestPolicy {
    static boolean current(boolean foreground,int generation,int expectedGeneration,Object identity,Object expectedIdentity,
                           boolean inSheet,int sheetGeneration,int expectedSheetGeneration) {
        return foreground&&generation==expectedGeneration&&identity==expectedIdentity&&(!inSheet||sheetGeneration==expectedSheetGeneration);
    }
    static boolean poll(boolean foreground,int page,boolean configured,boolean busy,boolean mutating,boolean sheetOpen) {
        return foreground&&page==0&&configured&&!busy&&!mutating&&!sheetOpen;
    }
    static final int SCRIPTS_PAGE=4;
    /** A job's output is followed only while its detail is visible on the scripts page. */
    static boolean jobPoll(boolean foreground,int page,boolean configured,boolean busy,boolean mutating,boolean sheetOpen,boolean following) {
        return foreground&&page==SCRIPTS_PAGE&&configured&&!busy&&!mutating&&!sheetOpen&&following;
    }
}
