package dev.servertimeclicker;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/**
 * 최근 측정 오차의 추이를 작게 그린다. 선이 평평하면 측정이 안정적이라는 뜻이고,
 * 들쭉날쭉하면 회선이나 기준 사이트를 의심할 때다. 초 경계를 놓친 회차는 붉은 점으로
 * 따로 표시한다. 그 회차는 오차가 ±500ms까지 벌어진다.
 *
 * <p>화면에 붙이지 않고도 그릴 수 있도록 Main에서 떼어 두었다.
 */
final class OffsetChart {
    private static final Color BACKGROUND = Color.web("#1b2029");
    private static final Color LINE = Color.web("#4a5568");
    private static final Color DOT_OK = Color.web("#7ee787");
    private static final Color DOT_MISSED = Color.web("#ff6b6b");
    private static final Color MEDIAN_LINE = Color.web("#20c7b5");
    private static final Color TEXT = Color.web("#6f7a8c");

    /**
     * 세로 눈금의 최소 폭. 이게 없으면 2 ms 흔들림도 화면 높이를 꽉 채워 그려져,
     * 500 ms 튄 것과 똑같이 심각해 보인다.
     */
    private static final double MIN_SPAN_MILLIS = 40;

    private static final double PAD_X = 6;
    private static final double PAD_Y = 8;

    /**
     * 캡션이 쓸 아래쪽 띠. 플롯과 겹치면 값이 아래로 몰렸을 때 둘 다 못 읽는다.
     * 크게 튄 회차가 눈금을 벌리면 나머지 값이 전부 플롯 바닥에 깔리므로 여유를 둔다.
     */
    private static final double CAPTION_HEIGHT = 16;

    private OffsetChart() {
    }

    static void draw(GraphicsContext g, double width, double height,
            List<ServerTimeSync.Measurement> samples, long medianMillis) {
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.setFont(Font.font("Consolas", 10));

        if (samples.isEmpty()) {
            g.setFill(TEXT);
            g.fillText("측정을 기다리는 중", PAD_X, height / 2 + 4);
            return;
        }

        long min = samples.get(0).offsetMillis();
        long max = min;
        int missed = 0;
        for (ServerTimeSync.Measurement sample : samples) {
            min = Math.min(min, sample.offsetMillis());
            max = Math.max(max, sample.offsetMillis());
            if (!sample.boundaryFound()) {
                missed++;
            }
        }

        // 실제 폭이 좁아도 최소 눈금까지 벌려, 잔잔한 흔들림이 과장되지 않게 한다.
        double center = (min + max) / 2.0;
        double span = Math.max(max - min, MIN_SPAN_MILLIS);
        double low = center - span / 2;
        double high = center + span / 2;
        double plotHeight = height - PAD_Y - CAPTION_HEIGHT;

        double medianY = PAD_Y + (high - medianMillis) / (high - low) * plotHeight;
        g.setStroke(MEDIAN_LINE);
        g.setLineWidth(1);
        g.setLineDashes(3, 3);
        g.strokeLine(PAD_X, medianY, width - PAD_X, medianY);
        g.setLineDashes();

        if (samples.size() > 1) {
            g.setStroke(LINE);
            g.beginPath();
            for (int i = 0; i < samples.size(); i++) {
                double x = xAt(i, samples.size(), width);
                double y = PAD_Y + (high - samples.get(i).offsetMillis()) / (high - low) * plotHeight;
                if (i == 0) {
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();
        }

        for (int i = 0; i < samples.size(); i++) {
            ServerTimeSync.Measurement sample = samples.get(i);
            double x = xAt(i, samples.size(), width);
            double y = PAD_Y + (high - sample.offsetMillis()) / (high - low) * plotHeight;
            g.setFill(sample.boundaryFound() ? DOT_OK : DOT_MISSED);
            g.fillOval(x - 2, y - 2, 4, 4);
        }

        g.setFill(TEXT);
        String caption = "최근 %d회 · 폭 %d ms".formatted(samples.size(), max - min);
        if (missed > 0) {
            caption += " · 경계 놓침 %d회".formatted(missed);
        }
        g.fillText(caption, PAD_X, height - 3);
    }

    /** 점이 하나뿐이면 가운데에 둔다. 0으로 나누지 않기 위해서이기도 하다. */
    private static double xAt(int index, int count, double width) {
        if (count <= 1) {
            return width / 2;
        }
        return PAD_X + index * (width - 2 * PAD_X) / (count - 1);
    }
}
