package dev.servertimeclicker;

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

    /** 목표까지 최소한 이만큼은 남아 있어야 한다. 목표 5초 전 정밀 동기화를 위한 여유. */
    public static final long MIN_LEAD_MILLIS = 10_000;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long MIN_SAFE_DELAY_MILLIS = 45;
    private static final long RTT_MARGIN_MILLIS = 35;
    private static final long MAX_SAFE_DELAY_MILLIS = 160;
    private static final long FINAL_MOUSE_MOVE_LEAD_MILLIS = 20;
    // press~release 사이 유지 시간. 대상 앱이 클릭을 확실히 인식하도록 최소한의 지연을 준다
    // (즉시 press+release하면 초고속 이벤트를 앱이 놓쳐 클릭이 씹히는 경우가 있다).
    private static final long CLICK_HOLD_MILLIS = 10;

    private final ServerTimeSync timeSync;

    public ClickScheduler(ServerTimeSync timeSync) {
        this.timeSync = timeSync;
    }

    /**
     * 목표 시각에 첫 좌표를 클릭하고, 이후 좌표를 순서대로 intervalMillis 간격으로 클릭한다.
     * 각 클릭 시각은 직전 클릭이 아니라 목표 시각을 기준으로 계산하므로 오차가 누적되지 않는다.
     *
     * @return 첫 클릭의 오차와 정밀 동기화 성공 여부
     */
    public ClickResult scheduleClicksAt(List<Point> points, long targetMillis, long intervalMillis)
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
        // 정밀 동기화부터 클릭까지, 주기 동기화가 offset을 덜 정밀한 값으로 덮어쓰지 못하게 막는다.
        timeSync.setBackgroundSyncPaused(true);
        try {
            boolean precisionSyncFailed = !syncPreciseOrKeepOffset();
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
                try {
                    if (CLICK_HOLD_MILLIS > 0) {
                        Thread.sleep(CLICK_HOLD_MILLIS);
                    }
                } finally {
                    // hold 중 취소로 인터럽트가 걸려도 버튼을 놓고 나간다.
                    // 놓지 않으면 마우스 왼쪽 버튼이 눌린 채로 시스템에 남는다.
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                }

                long delta = timeSync.getServerTimeMillis() - targetMillis;
                System.out.printf("클릭 %d/%d 완료 (%d, %d). 목표 대비 %+d ms%n",
                        i + 1, points.size(), point.x(), point.y(), delta);
                if (i == 0) {
                    firstDelta = delta;
                }
            }

            return new ClickResult(firstDelta, precisionSyncFailed);
        } finally {
            timeSync.setBackgroundSyncPaused(false);
        }
    }

    /**
     * 목표 직전 정밀 동기화. 실패해도 예약을 포기하지 않는다. 직전까지 쓰던 offset은
     * 늦어도 8초 전에 잰 값이라 그대로 클릭해도 되는데, 여기서 예외를 올리면 한 번뿐인
     * 예약이 복구 가능한 네트워크 오류 하나로 통째로 날아간다. 하필 이 순간이 대상
     * 사이트가 가장 붐비는 때라 실패 확률도 평소보다 높다. 취소는 그대로 올린다.
     *
     * @return 정밀 동기화에 성공했으면 true
     */
    private boolean syncPreciseOrKeepOffset() throws InterruptedException {
        try {
            timeSync.syncPrecise();
            return true;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("정밀 동기화 실패, 직전 offset으로 진행: " + e.getMessage());
            return false;
        }
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

        if (secondsTo30 * 1000 <= MIN_LEAD_MILLIS) {
            secondsTo30 += 1800;
        }

        return (now / 1000 + secondsTo30) * 1000L;
    }

    /**
     * 입력한 시각을 절대 시각으로 바꾼다. 이미 지난 시각은 다음 날로 본다(HH:mm:ss만
     * 받으므로 그 해석뿐이다).
     *
     * @throws IllegalArgumentException 목표가 지금부터 MIN_LEAD_MILLIS 안쪽일 때.
     *         조용히 다음 날로 미루면 사용자가 노린 순간을 통째로 놓친다.
     */
    public long calcTargetMillis(LocalTime targetTime) {
        long nowMillis = timeSync.getServerTimeMillis();
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(KST);
        ZonedDateTime target = now.toLocalDate().atTime(targetTime).atZone(KST);

        if (target.isBefore(now)) {
            target = target.plusDays(1);
        }

        long targetMillis = target.toInstant().toEpochMilli();
        if (targetMillis - nowMillis < MIN_LEAD_MILLIS) {
            throw new IllegalArgumentException(
                    "목표 시각까지 %d초도 남지 않았습니다. 목표 5초 전 정밀 동기화가 필요하니 더 뒤로 잡으세요."
                            .formatted(MIN_LEAD_MILLIS / 1000));
        }
        return targetMillis;
    }

    private void waitUntilRough(long targetMillis) throws InterruptedException {
        while (true) {
            throwIfCancelled();
            long remaining = targetMillis - timeSync.getServerTimeMillis();
            if (remaining <= 0) {
                return;
            }
            Thread.sleep(Math.min(remaining, 100));
        }
    }

    private void waitUntilPrecise(long targetMillis) throws InterruptedException {
        while (true) {
            throwIfCancelled();
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

    /**
     * 취소되었으면 중단한다. 대기가 sleep으로만 이뤄지지 않고(yield·spin, 그리고 이미 시각이
     * 지나 곧바로 반환하는 경우) 인터럽트가 그냥 지나칠 수 있어, 클릭마다 직접 확인한다.
     */
    private static void throwIfCancelled() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("예약이 취소되었습니다.");
        }
    }

    /**
     * 클릭 결과. 오차만으로는 "정밀 동기화를 못 한 채 이전 기준으로 눌렀다"는 사실을
     * 화면에 전할 수 없어 함께 돌려준다.
     */
    public record ClickResult(long firstDeltaMillis, boolean precisionSyncFailed) {
    }
}
