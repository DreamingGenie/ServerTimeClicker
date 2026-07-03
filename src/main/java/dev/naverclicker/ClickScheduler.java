package dev.naverclicker;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ClickScheduler {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long MIN_SAFE_DELAY_MILLIS = 45;
    private static final long RTT_MARGIN_MILLIS = 35;
    private static final long MAX_SAFE_DELAY_MILLIS = 160;
    private static final long FINAL_MOUSE_MOVE_LEAD_MILLIS = 20;

    private final ServerTimeSync timeSync;

    public ClickScheduler(ServerTimeSync timeSync) {
        this.timeSync = timeSync;
    }

    public long scheduleClick(int x, int y) throws Exception {
        long targetMillis = calcNextTargetMillis();
        return scheduleClickAt(x, y, targetMillis);
    }

    public long scheduleClickAt(int x, int y, long targetMillis) throws Exception {
        LocalDateTime targetTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMillis), KST);
        System.out.printf("예약 기준 시각: %02d:%02d:%02d.000%n",
                targetTime.getHour(), targetTime.getMinute(), targetTime.getSecond());

        waitUntilRough(targetMillis - 5000);
        timeSync.syncPrecise();
        long safeDelayMillis = getSafeDelayMillis();
        long clickMillis = targetMillis + safeDelayMillis;
        System.out.printf("실제 클릭 목표: 예약 기준 +%d ms%n", safeDelayMillis);

        Robot robot = new Robot();
        robot.setAutoDelay(0);
        robot.mouseMove(x, y);

        waitUntilPrecise(clickMillis - FINAL_MOUSE_MOVE_LEAD_MILLIS);
        robot.mouseMove(x, y);
        waitUntilPrecise(clickMillis);

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        long clickedServerMillis = timeSync.getServerTimeMillis();
        System.out.printf("클릭 완료. 목표 대비 %+d ms%n", clickedServerMillis - targetMillis);
        return clickedServerMillis - targetMillis;
    }

    public long getSafeDelayMillis() {
        long rtt = timeSync.getLastRoundTripMillis();
        if (rtt < 0) {
            return MIN_SAFE_DELAY_MILLIS;
        }

        long calculatedDelay = rtt + RTT_MARGIN_MILLIS;
        long boundedDelay = Math.max(MIN_SAFE_DELAY_MILLIS, calculatedDelay);
        return Math.min(MAX_SAFE_DELAY_MILLIS, boundedDelay);
    }

    public long calcNextTargetMillis() {
        long now = timeSync.getServerTimeMillis();
        long secondsInHour = (now / 1000) % 3600;
        long secondsTo30 = 1800 - (secondsInHour % 1800);

        if (secondsTo30 <= 10) {
            secondsTo30 += 1800;
        }

        return (now / 1000 + secondsTo30) * 1000L;
    }

    public long calcTargetMillis(LocalTime targetTime) {
        ZonedDateTime now = Instant.ofEpochMilli(timeSync.getServerTimeMillis()).atZone(KST);
        ZonedDateTime target = now.toLocalDate().atTime(targetTime).atZone(KST);

        if (!target.isAfter(now.plusSeconds(10))) {
            target = target.plusDays(1);
        }

        return target.toInstant().toEpochMilli();
    }

    private void waitUntilRough(long targetMillis) throws InterruptedException {
        while (true) {
            long remaining = targetMillis - timeSync.getServerTimeMillis();
            if (remaining <= 0) {
                return;
            }
            Thread.sleep(Math.min(remaining, 100));
        }
    }

    private void waitUntilPrecise(long targetMillis) throws InterruptedException {
        while (true) {
            long remaining = targetMillis - timeSync.getServerTimeMillis();
            if (remaining <= 0) {
                return;
            }

            if (remaining > 25) {
                Thread.sleep(Math.min(remaining - 20, 5));
            } else if (remaining > 3) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
