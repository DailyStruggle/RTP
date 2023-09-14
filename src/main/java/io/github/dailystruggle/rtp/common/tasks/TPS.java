package io.github.dailystruggle.rtp.common.tasks;

public class TPS implements Runnable {
    public static final long[] TICKS = new long[600];
    public static int TICK_COUNT = 0;

    public static double getTPS( int ticks ) {
        long elapsed = timeSinceTick( ticks );
        return ticks / ( (double ) elapsed / 1000.0D );
    }

    public static long timeSinceTick( int ticks ) {
        if ( TICK_COUNT < ticks ) {
            return 50;
        }
        int target = ( (TICK_COUNT - 1 ) - ticks ) % TICKS.length;
        if ( target < 0 ) return 50;

        return System.currentTimeMillis() - TICKS[target];
    }

    public void run() {
        TICKS[( TICK_COUNT % TICKS.length )] = System.currentTimeMillis();

        TICK_COUNT += 1;
    }
}
