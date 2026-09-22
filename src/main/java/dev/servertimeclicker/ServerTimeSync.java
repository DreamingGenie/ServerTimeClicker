package dev.servertimeclicker;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class ServerTimeSync {
    public static final String DEFAULT_URL = "https://map.naver.com";

    private static final DateTimeFormatter DATE_HEADER_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_HEADER_FORMAT_OBSOLETE =
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 중앙값을 낼 때 쓰는 최근 측정 개수. 한 번의 측정에는 수십 ms 잡음이 섞이고, 초
     * 경계를 놓친 회차는 500 ms까지 어긋난다. 실측 14회로 비교했을 때 최신값 하나는
     * 표준편차 15.7 ms, 최근 5회 중앙값은 8.4 ms였다.
     */
    private static final int MEDIAN_SAMPLE_COUNT = 5;

    /** 중앙값에 이보다 오래된 측정은 넣지 않는다. 서버 시계가 실제로 옮겨 가면 따라가야 한다. */
    private static final long MEDIAN_MAX_AGE_MILLIS = 3 * 60 * 1000;

    /** 이력 보관 개수. 중앙값은 이 중 최근 것만 쓰고, 나머지는 추이를 보여주는 데 쓴다. */
    private static final int HISTORY_SIZE = 30;

    // 일부 사이트(CDN/WAF)는 기본 Java UA를 봇으로 보고 차단하므로 브라우저 UA로 요청한다.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private volatile String targetUrl = DEFAULT_URL;
    private volatile boolean headSupported = true;

    // 인증서 체인이 불완전한 사이트를 사용자가 명시적으로 신뢰할 때만 검증을 완화한다.
    private volatile boolean allowInsecureTls = false;
    // 정밀 동기화~클릭 사이 임계 구간 동안 주기 동기화가 offset을 덮어쓰지 못하게 막는다.
    private volatile boolean backgroundSyncPaused = false;
    private static volatile SSLSocketFactory trustAllSocketFactory;

    /** 현재 offsetMillis를 측정한 URL. 아직 한 번도 동기화하지 않았으면 null. */
    private volatile String syncedUrl = null;

    /**
     * 최근 측정 이력. apply()를 부르는 경로가 모두 synchronized라 인스턴스 락이 지킨다.
     * 읽는 쪽(getServerTimeMillis)은 아래 offsetMillis만 보므로 락을 잡지 않는다.
     */
    private final Deque<OffsetSample> offsetHistory = new ArrayDeque<>();

    /**
     * 화면이 읽는 이력 스냅샷. 화면은 FX 스레드에서 읽는데 위 목록은 인스턴스 락이
     * 지키고 동기화가 그 락을 최대 2.5초 쥐고 있어, 그대로 열어 주면 화면이 멈춘다.
     */
    private volatile List<Measurement> recentMeasurements = List.of();

    /** 이력의 중앙값. 시계 계산에 실제로 쓰이는 값이다. */
    private volatile long offsetMillis = 0;

    private volatile long lastRoundTripMillis = -1;
    private volatile String lastStatus = "동기화 전";
    private volatile long lastSyncLocalMillis = 0;
    private volatile long syncCount = 0;

    public String getTargetUrl() {
        return targetUrl;
    }

    public boolean isAllowInsecureTls() {
        return allowInsecureTls;
    }

    public void setAllowInsecureTls(boolean allowInsecureTls) {
        this.allowInsecureTls = allowInsecureTls;
    }

    /**
     * 정밀 동기화 후 클릭까지의 임계 구간 동안 true로 설정하면, 주기 동기화가
     * 그 사이 offset을 덜 정밀한 값으로 덮어쓰는 것을 막는다.
     */
    public void setBackgroundSyncPaused(boolean paused) {
        this.backgroundSyncPaused = paused;
    }

    public boolean isBackgroundSyncPaused() {
        return backgroundSyncPaused;
    }

    /** true면 인증서 체인 문제(PKIX 경로 구성 실패)로 인한 실패임을 나타낸다. */
    public static boolean isCertificateChainError(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
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
            // 리다이렉트로 실제 응답한 주소를 기준으로 승격한다. 그러지 않으면 샘플마다
            // 리다이렉트를 한 번 더 타서 왕복이 두 번이 되고, 그만큼 샘플 간격이 벌어져
            // 초 경계를 좁힐 수 있는 폭이 나빠진다. 바뀐 주소는 주소 칸에 그대로 보인다.
            targetUrl = fetchDateHeader().resolvedUrl();
        } catch (Exception e) {
            targetUrl = previous;
            headSupported = previousHeadSupported;
            throw new IOException(describeFailure(normalized, e), e);
        }

        if (!targetUrl.equals(previous)) {
            // 다른 서버의 측정치를 한 중앙값에 섞으면 안 된다.
            offsetHistory.clear();
            lastStatus = "%s 동기화 전".formatted(hostOf(targetUrl));
        }
    }

    /** 현재 오프셋이 지금 지정된 URL로 측정된 것인지. false면 시계를 신뢰할 수 없다. */
    public boolean isSyncedForTarget() {
        String synced = syncedUrl;
        return synced != null && synced.equals(targetUrl);
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
        // 락 안에서 다시 본다. 호출부에서만 검사하면 검사와 동기화 사이에 정밀 동기화가
        // 끼어들 수 있어, 막으려던 offset 덮어쓰기가 그대로 일어난다.
        if (backgroundSyncPaused) {
            return;
        }

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
        long now = System.currentTimeMillis();
        record(result, now);
        offsetMillis = medianOffset();

        lastRoundTripMillis = result.roundTripMillis();
        lastSyncLocalMillis = now;
        syncedUrl = targetUrl;
        syncCount++;
        // 측정 개수와 폭은 바로 아래 추이 그래프가 보여준다. 여기 또 적으면 줄이 넘쳐
        // 뒷부분이 잘린다.
        lastStatus = "%s (%s) | 오차 %+d ms | RTT %d ms%s".formatted(
                label, hostOf(syncedUrl), offsetMillis, lastRoundTripMillis,
                result.boundaryFound() ? "" : " | 이번 회차 초 경계 못 잡음(±500ms)");
        System.out.printf("%s | 이번 측정 %+d ms%n", lastStatus, result.offsetMillis());
    }

    private void record(SyncResult result, long now) {
        offsetHistory.addLast(new OffsetSample(result.offsetMillis(), now, result.boundaryFound()));
        while (offsetHistory.size() > HISTORY_SIZE) {
            offsetHistory.removeFirst();
        }

        List<Measurement> snapshot = new ArrayList<>(offsetHistory.size());
        for (OffsetSample sample : offsetHistory) {
            snapshot.add(new Measurement(sample.offsetMillis(), sample.boundaryFound()));
        }
        recentMeasurements = List.copyOf(snapshot);
    }

    /** 화면 표시용 최근 측정 이력. 오래된 것이 앞, 최신이 뒤. 락을 잡지 않는다. */
    public List<Measurement> getRecentMeasurements() {
        return recentMeasurements;
    }

    /**
     * 최근 측정의 중앙값. 한 회차가 크게 빗나가도 나머지가 밀어낸다. 초 경계를 잡은
     * 측정이 하나라도 있으면 그것들만 쓴다. 초 중앙으로 때린 값(±500ms)은 경계를 잡은
     * 값과 섞일 자격이 없기 때문이다.
     */
    private long medianOffset() {
        long now = System.currentTimeMillis();
        List<OffsetSample> recent = new ArrayList<>(MEDIAN_SAMPLE_COUNT);
        Iterator<OffsetSample> newestFirst = offsetHistory.descendingIterator();
        while (newestFirst.hasNext() && recent.size() < MEDIAN_SAMPLE_COUNT) {
            OffsetSample sample = newestFirst.next();
            if (now - sample.measuredAtMillis() > MEDIAN_MAX_AGE_MILLIS) {
                // 이력은 시간순이라 이보다 앞엣것은 모두 더 오래됐다.
                break;
            }
            recent.add(sample);
        }
        if (recent.isEmpty() && !offsetHistory.isEmpty()) {
            // 전부 오래됐어도 0을 돌려주는 것보다는 가장 최근 값이 낫다.
            recent.add(offsetHistory.peekLast());
        }

        List<Long> values = recent.stream()
                .filter(OffsetSample::boundaryFound)
                .map(OffsetSample::offsetMillis)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            values = recent.stream()
                    .map(OffsetSample::offsetMillis)
                    .sorted()
                    .toList();
        }

        int n = values.size();
        if (n % 2 == 1) {
            return values.get(n / 2);
        }
        return (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
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

            // 정확히 1초 뛴 경우만 초 경계로 인정한다. 샘플이 지연돼 2초 이상 건너뛰면
            // 실제 경계는 current 직전이지 두 샘플의 중간점이 아니라서, 그대로 쓰면
            // 최대 1초 가까이 어긋난 offset을 정밀한 값인 양 적용하게 된다.
            if (current.serverMillis() - previous.serverMillis() == 1000) {
                long localBoundary = (previous.middleMillis() + current.middleMillis()) / 2;
                long offset = current.serverMillis() - localBoundary;
                long rtt = Math.min(previous.roundTripMillis(), current.roundTripMillis());
                return new SyncResult(offset, rtt, true);
            }

            previous = current;
        }

        // Fallback: Date headers are second-precision, so place the server time at the
        // middle of that second. This avoids the systematic nearly-1s slow bias.
        Sample best = samples.stream()
                .min(Comparator.comparingLong(Sample::roundTripMillis))
                .orElseThrow(() -> new IOException("Date 헤더 샘플을 얻지 못했습니다."));
        long offset = best.serverMillis() + 500 - best.middleMillis();
        return new SyncResult(offset, best.roundTripMillis(), false);
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
        if (allowInsecureTls && conn instanceof HttpsURLConnection httpsConn) {
            httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true);
        }
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
            return new Sample(parseDateHeader(dateHeader), before, after, conn.getURL().toString());
        } finally {
            // disconnect()를 부르지 않는다. 부르면 소켓이 버려져 다음 샘플이 TLS
            // 핸드셰이크부터 다시 한다. 기본 경로인 HEAD는 본문이 없어 아래 읽기가
            // 곧바로 EOF에 닿으므로 연결이 재사용 가능한 상태로 돌아간다.
            // (실측: 요청 최소 소요가 58ms -> 45ms. 평균은 회선 편차에 묻힌다.)
            drainQuietly(conn);
        }
    }

    /**
     * 연결을 정리한다. 본문은 받지 않는 것이 원칙이라 상한을 두고 읽는다. HEAD 응답은
     * 본문이 없어 재사용으로 이어지고, GET 폴백에서 본문이 남으면 JDK가 소켓을 버린다.
     */
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

    /**
     * 인증서 체인이 불완전한 사이트를 사용자가 명시적으로 신뢰하기로 한 경우에만 사용되는
     * 검증 생략 소켓 팩토리. 이 인스턴스는 개별 연결에만 설정되며 JVM 기본 신뢰 설정에는
     * 영향을 주지 않는다.
     */
    private static SSLSocketFactory getTrustAllSocketFactory() throws Exception {
        SSLSocketFactory factory = trustAllSocketFactory;
        if (factory == null) {
            synchronized (ServerTimeSync.class) {
                factory = trustAllSocketFactory;
                if (factory == null) {
                    TrustManager[] trustAll = new TrustManager[]{
                            new X509TrustManager() {
                                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                                }

                                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                                }

                                public X509Certificate[] getAcceptedIssuers() {
                                    return new X509Certificate[0];
                                }
                            }
                    };
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, trustAll, new SecureRandom());
                    factory = context.getSocketFactory();
                    trustAllSocketFactory = factory;
                }
            }
        }
        return factory;
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

    /** 한 번의 동기화가 내놓은 오차와, 그것이 초 경계를 잡아 낸 값인지. */
    private record OffsetSample(long offsetMillis, long measuredAtMillis, boolean boundaryFound) {
    }

    /** 화면에 추이를 그리기 위한 측정 한 건. */
    public record Measurement(long offsetMillis, boolean boundaryFound) {
    }

    /** resolvedUrl은 리다이렉트를 따라간 끝에 실제로 응답한 주소다. */
    private record Sample(long serverMillis, long beforeMillis, long afterMillis, String resolvedUrl) {
        long middleMillis() {
            return (beforeMillis + afterMillis) / 2;
        }

        long roundTripMillis() {
            return afterMillis - beforeMillis;
        }
    }

    /** boundaryFound가 false면 초 경계를 못 잡고 초 중앙으로 때린 값이라 ±500ms까지 벌어진다. */
    private record SyncResult(long offsetMillis, long roundTripMillis, boolean boundaryFound) {
    }
}
