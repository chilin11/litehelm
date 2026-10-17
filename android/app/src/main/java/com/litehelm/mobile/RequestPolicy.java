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
}
