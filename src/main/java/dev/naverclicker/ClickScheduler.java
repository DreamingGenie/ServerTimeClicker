package dev.naverclicker;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class ClickScheduler {
    public static final long DEFAULT_INTERVAL_MILLIS = 300;
    public static final long MIN_INTERVAL_MILLIS = 20;
    public static final long MAX_INTERVAL_MILLIS = 10000;

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
        return scheduleClicksAt(List.of(new Point(x, y)), targetMillis, DEFAULT_INTERVAL_MILLIS);
    }

    /**
     * 목표 시각에 첫 좌표를 클릭하고, 이후 좌표를 순서대로 intervalMillis 간격으로 클릭한다.
     * 각 클릭 시각은 직전 클릭이 아니라 목표 시각을 기준으로 계산하므로 오차가 누적되지 않는다.
     *
     * @return 첫 클릭의 목표 대비 오차(ms)
     */
    public long scheduleClicksAt(List<Point> points, long targetMillis, long intervalMillis)
            throws Exception {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("클릭할 좌표가 없습니다.");
        }

        long interval = Math.clamp(intervalMillis, MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS);
        LocalDateTime targetTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMillis), KST);
        System.out.printf("예약 기준 시각: %02d:%02d:%02d.000 | 좌표 %d개 | 간격 %d ms%n",
                targetTime.getHour(), targetTime.getMinute(), targetTime.getSecond(),
                points.size(), interval);

        waitUntilRough(targetMillis - 5000);
        timeSync.syncPrecise();
        long safeDelayMillis = getSafeDelayMillis();
        long firstClickMillis = targetMillis + safeDelayMillis;
        System.out.printf("실제 클릭 목표: 예약 기준 +%d ms%n", safeDelayMillis);

        Robot robot = new Robot();
        robot.setAutoDelay(0);

        Point first = points.get(0);
        robot.mouseMove(first.x(), first.y());
        waitUntilPrecise(firstClickMillis - FINAL_MOUSE_MOVE_LEAD_MILLIS);
        robot.mouseMove(first.x(), first.y());

        long firstDelta = 0;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            long clickMillis = firstClickMillis + i * interval;

            if (i > 0) {
                // 다음 좌표로 미리 이동해 두고 클릭 시각을 기다린다.
                waitUntilPrecise(clickMillis - FINAL_MOUSE_MOVE_LEAD_MILLIS);
                robot.mouseMove(point.x(), point.y());
            }

            waitUntilPrecise(clickMillis);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

            long delta = timeSync.getServerTimeMillis() - targetMillis;
            System.out.printf("클릭 %d/%d 완료 (%d, %d). 목표 대비 %+d ms%n",
                    i + 1, points.size(), point.x(), point.y(), delta);
            if (i == 0) {
                firstDelta = delta;
            }
        }

        return firstDelta;
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
