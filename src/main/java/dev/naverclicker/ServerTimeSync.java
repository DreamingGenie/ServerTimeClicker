package dev.naverclicker;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ServerTimeSync {
    private static final String TARGET_URL = "https://map.naver.com";
    private static final DateTimeFormatter DATE_HEADER_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private volatile long offsetMillis = 0;
    private volatile long lastRoundTripMillis = -1;
    private volatile String lastStatus = "동기화 전";
    private volatile long lastSyncLocalMillis = 0;
    private volatile long syncCount = 0;

    public synchronized void sync(int attempts) throws Exception {
        SyncResult result = syncAtSecondBoundary(Math.max(40, attempts * 20), 12, 2500);
        apply(result, "초기 동기화 완료");
    }

    public synchronized void syncBackground() throws Exception {
        SyncResult result = syncAtSecondBoundary(70, 18, 2200);
        apply(result, "주기 동기화 완료");
    }

    public synchronized void syncPrecise() throws Exception {
        long millis = floorMod(getServerTimeMillis(), 1000);
        if (millis < 850) {
            Thread.sleep(850 - millis);
        }

        SyncResult result = syncAtSecondBoundary(90, 4, 1200);
        apply(result, "정밀 동기화 완료");
    }

    public long getServerTimeMillis() {
        return System.currentTimeMillis() + offsetMillis;
    }

    public LocalDateTime getServerTimeKST() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(getServerTimeMillis()), KST);
    }

    public long getOffsetMillis() {
        return offsetMillis;
    }

    public long getLastRoundTripMillis() {
        return lastRoundTripMillis;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public long getLastSyncLocalMillis() {
        return lastSyncLocalMillis;
    }

    public long getSyncCount() {
        return syncCount;
    }

    private void apply(SyncResult result, String label) {
        offsetMillis = result.offsetMillis();
        lastRoundTripMillis = result.roundTripMillis();
        lastSyncLocalMillis = System.currentTimeMillis();
        syncCount++;
        lastStatus = "%s | 오차 %+d ms | RTT %d ms".formatted(
                label, offsetMillis, lastRoundTripMillis);
        System.out.println(lastStatus);
    }

    private SyncResult syncAtSecondBoundary(int maxAttempts, long intervalMillis, long maxWaitMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        Sample previous = fetchDateHeader();
        List<Sample> samples = new ArrayList<>();
        samples.add(previous);

        for (int i = 1; i < maxAttempts && System.currentTimeMillis() < deadline; i++) {
            Thread.sleep(intervalMillis);
            Sample current = fetchDateHeader();
            samples.add(current);

            if (current.serverMillis() > previous.serverMillis()) {
                long localBoundary = (previous.middleMillis() + current.middleMillis()) / 2;
                long offset = current.serverMillis() - localBoundary;
                long rtt = Math.min(previous.roundTripMillis(), current.roundTripMillis());
                return new SyncResult(offset, rtt);
            }

            previous = current;
        }

        // Fallback: Date headers are second-precision, so place the server time at the
        // middle of that second. This avoids the systematic nearly-1s slow bias.
        Sample best = samples.stream()
                .min(Comparator.comparingLong(Sample::roundTripMillis))
                .orElseThrow(() -> new IOException("Date 헤더 샘플을 얻지 못했습니다."));
        long offset = best.serverMillis() + 500 - best.middleMillis();
        return new SyncResult(offset, best.roundTripMillis());
    }

    private Sample fetchDateHeader() throws Exception {
        long before = System.currentTimeMillis();
        URL url = URI.create(TARGET_URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(1000);
        conn.setReadTimeout(1000);
        conn.setUseCaches(false);
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Pragma", "no-cache");

        try {
            conn.connect();
            String dateHeader = conn.getHeaderField("Date");
            long after = System.currentTimeMillis();

            if (dateHeader == null || dateHeader.isBlank()) {
                throw new IOException("map.naver.com 응답에서 Date 헤더를 받지 못했습니다.");
            }

            long serverMillis = ZonedDateTime.parse(dateHeader, DATE_HEADER_FORMAT)
                    .toInstant()
                    .toEpochMilli();
            return new Sample(serverMillis, before, after);
        } finally {
            conn.disconnect();
        }
    }

    private static long floorMod(long value, long mod) {
        long result = value % mod;
        return result < 0 ? result + mod : result;
    }

    private record Sample(long serverMillis, long beforeMillis, long afterMillis) {
        long middleMillis() {
            return (beforeMillis + afterMillis) / 2;
        }

        long roundTripMillis() {
            return afterMillis - beforeMillis;
        }
    }

    private record SyncResult(long offsetMillis, long roundTripMillis) {
    }
}
