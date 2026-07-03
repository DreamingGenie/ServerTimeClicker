package dev.naverclicker;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;

public class TimeDisplay implements Runnable {

    private final ServerTimeSync timeSync;
    private volatile boolean running = true; // 스레드 종료 신호

    public TimeDisplay(ServerTimeSync timeSync) {
        this.timeSync = timeSync;
    }

    @Override
    public void run() {
        while (running) {
            // 현재 서버 시간 계산
            long serverMillis = timeSync.getServerTimeMillis();
            LocalDateTime now = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(serverMillis),
                    ZoneId.of("Asia/Seoul")
            );

            // 밀리초 단위까지 추출
            int millis = (int)(serverMillis % 1000);

            // \r : 줄 맨 앞으로 돌아가서 덮어쓰기 (줄바꿈 없이 실시간 갱신)
            System.out.printf("\r🕐 서버 시간: %04d-%02d-%02d %02d:%02d:%02d.%03d  ",
                    now.getYear(),
                    now.getMonthValue(),
                    now.getDayOfMonth(),
                    now.getHour(),
                    now.getMinute(),
                    now.getSecond(),
                    millis
            );

            try {
                Thread.sleep(10); // 10ms마다 갱신 (1ms는 CPU 과부하)
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** 실시간 출력 중지 (다음 단계로 넘어갈 때 호출) */
    public void stop() {
        running = false;
        System.out.println(); // 마지막 줄 이후 줄바꿈
    }
}