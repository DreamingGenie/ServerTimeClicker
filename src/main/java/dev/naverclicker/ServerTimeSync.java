package dev.naverclicker;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ServerTimeSync {
    public static final String DEFAULT_URL = "https://map.naver.com";

    private static final DateTimeFormatter DATE_HEADER_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_HEADER_FORMAT_OBSOLETE =
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 일부 사이트(CDN/WAF)는 기본 Java UA를 봇으로 보고 차단하므로 브라우저 UA로 요청한다.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private volatile String targetUrl = DEFAULT_URL;
    private volatile boolean headSupported = true;

    /** 현재 offsetMillis를 측정한 URL. 아직 한 번도 동기화하지 않았으면 null. */
    private volatile String syncedUrl = null;

    private volatile long offsetMillis = 0;
    private volatile long lastRoundTripMillis = -1;
    private volatile String lastStatus = "동기화 전";
    private volatile long lastSyncLocalMillis = 0;
    private volatile long syncCount = 0;

    public String getTargetUrl() {
        return targetUrl;
    }

    /**
     * 대상 URL을 바꾸고 Date 헤더를 실제로 주는지 즉시 확인한다.
     * 확인에 실패하면 URL은 이전 값으로 되돌리고 예외를 던진다.
     *
     * <p>오프셋은 여기서 건드리지 않는다. 오프셋 0은 "모름"이 아니라 "서버 시간 = 내 PC 시간"이라
     * 초기화해 버리면 동기화되지 않은 로컬 시간이 서버 시간인 척 표시된다. 대신 syncedUrl이
     * 새 URL과 달라지므로 {@link #isSyncedForTarget()}이 false가 되어 동기화 전임이 드러난다.
     */
    public synchronized void setTargetUrl(String rawUrl) throws IOException {
        String normalized = normalizeUrl(rawUrl);
        String previous = targetUrl;
        boolean previousHeadSupported = headSupported;

        targetUrl = normalized;
        headSupported = true;
        try {
            fetchDateHeader();
        } catch (Exception e) {
            targetUrl = previous;
            headSupported = previousHeadSupported;
            throw new IOException(describeFailure(normalized, e), e);
        }

        if (!normalized.equals(previous)) {
            lastStatus = "%s 동기화 전".formatted(hostOf(normalized));
        }
    }

    /** 현재 오프셋이 지금 지정된 URL로 측정된 것인지. false면 시계를 신뢰할 수 없다. */
    public boolean isSyncedForTarget() {
        String synced = syncedUrl;
        return synced != null && synced.equals(targetUrl);
    }

    /** 현재 오프셋을 측정한 사이트의 호스트. 아직 동기화 전이면 null. */
    public String getSyncedHost() {
        String synced = syncedUrl;
        return synced == null ? null : hostOf(synced);
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    static String normalizeUrl(String rawUrl) throws IOException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IOException("URL을 입력하세요.");
        }

        String candidate = rawUrl.trim();
        if (!candidate.matches("(?i)^https?://.*")) {
            candidate = "https://" + candidate;
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new IOException("URL 형식이 올바르지 않습니다: " + rawUrl);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("URL에 호스트가 없습니다: " + rawUrl);
        }
        return uri.toString();
    }

    private static String describeFailure(String url, Exception e) {
        String host = URI.create(url).getHost();
        if (e instanceof NoDateHeaderException) {
            return "%s 는 응답에 Date 헤더를 주지 않습니다. 서버 시간을 읽을 수 없으니 다른 URL을 사용하세요."
                    .formatted(host);
        }
        return "%s 에 접속하지 못했습니다: %s".formatted(host, e.getMessage());
    }

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
        syncedUrl = targetUrl;
        syncCount++;
        lastStatus = "%s (%s) | 오차 %+d ms | RTT %d ms".formatted(
                label, hostOf(syncedUrl), offsetMillis, lastRoundTripMillis);
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
        if (headSupported) {
            try {
                return request("HEAD");
            } catch (MethodNotAllowedException e) {
                // 보안 설정상 HEAD를 막는 사이트가 있어 GET으로 내려간다.
                headSupported = false;
            }
        }
        return request("GET");
    }

    private Sample request(String method) throws Exception {
        long before = System.currentTimeMillis();
        URL url = URI.create(targetUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Pragma", "no-cache");
        if ("GET".equals(method)) {
            // 본문 전체를 받지 않도록 첫 바이트만 요청한다. 무시되더라도 본문은 읽지 않는다.
            conn.setRequestProperty("Range", "bytes=0-0");
        }

        try {
            conn.connect();
            int status = conn.getResponseCode();
            String dateHeader = conn.getHeaderField("Date");
            long after = System.currentTimeMillis();

            if (dateHeader == null || dateHeader.isBlank()) {
                if (status == 405 || status == 501) {
                    throw new MethodNotAllowedException(method + " 요청이 허용되지 않습니다.");
                }
                throw new NoDateHeaderException(
                        "응답(HTTP %d)에 Date 헤더가 없습니다.".formatted(status));
            }

            // 4xx/5xx여도 Date 헤더는 서버가 찍어준 값이므로 그대로 쓴다.
            return new Sample(parseDateHeader(dateHeader), before, after);
        } finally {
            drainQuietly(conn);
            conn.disconnect();
        }
    }

    private static void drainQuietly(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()) {
            if (in != null) {
                in.readNBytes(1024);
            }
        } catch (IOException ignored) {
            // 시간 측정에 영향이 없는 정리 단계이므로 무시한다.
        }
    }

    static long parseDateHeader(String dateHeader) throws IOException {
        String value = dateHeader.trim();
        for (DateTimeFormatter formatter : List.of(DATE_HEADER_FORMAT, DATE_HEADER_FORMAT_OBSOLETE)) {
            try {
                return ZonedDateTime.parse(value, formatter).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // 다음 형식으로 시도한다.
            }
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IOException("Date 헤더를 해석할 수 없습니다: " + dateHeader);
        }
    }

    private static long floorMod(long value, long mod) {
        long result = value % mod;
        return result < 0 ? result + mod : result;
    }

    private static class MethodNotAllowedException extends IOException {
        MethodNotAllowedException(String message) {
            super(message);
        }
    }

    private static class NoDateHeaderException extends IOException {
        NoDateHeaderException(String message) {
            super(message);
        }
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
