package org.jumpserver.chen.framework.utils;

public class TimeUtils {
    public static long getNowUnixNanoTIme() {
        var now = java.time.Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }
}
