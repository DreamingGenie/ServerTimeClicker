package dev.naverclicker;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class Main extends Application {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_INPUT_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerTimeSync timeSync = new ServerTimeSync();
    private ClickScheduler scheduler;

    private Label timeLabel;
    private Label syncStatusLabel;
    private Label coordLabel;
    private Label countdownLabel;
    private Label clickResultLabel;
    private Label targetLabel;
    private Button startButton;
    private TextField targetTimeField;

    private volatile int mappedX = -1;
    private volatile int mappedY = -1;

    @Override
    public void start(Stage stage) {
        Label title = new Label("네이버 지도 예약 클릭");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #f5f7fb;");

        Label subtitle = new Label("좌표를 지정하고 목표 시각을 확인한 뒤 예약 시작을 누르면 됩니다.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca8ba;");

        timeLabel = new Label("동기화 중...");
        timeLabel.setMinWidth(320);
        timeLabel.setAlignment(Pos.CENTER);
        timeLabel.setStyle("""
                -fx-font-size: 36px;
                -fx-font-weight: bold;
                -fx-text-fill: #20c7b5;
                -fx-font-family: Consolas, monospace;
                """);

        syncStatusLabel = new Label("1. 서버 시간 동기화 중");
        syncStatusLabel.setStyle(statusStyle("#c4cad4"));

        coordLabel = new Label("2. 좌표 지정: 클릭할 버튼 위에 마우스를 올리고 Ctrl + F1을 누르세요.");
        coordLabel.setWrapText(true);
        coordLabel.setStyle(statusStyle("#ffd166"));

        targetLabel = new Label("3. 목표 시각: 다음 30분 정각 또는 직접 입력");
        targetLabel.setStyle(statusStyle("#c4cad4"));

        targetTimeField = new TextField();
        targetTimeField.setPromptText("예: 15:30:00");
        targetTimeField.setText(formatMillisAsTime(timeSync.getServerTimeMillis()));
        targetTimeField.setStyle("""
                -fx-background-color: #222936;
                -fx-text-fill: #f5f7fb;
                -fx-prompt-text-fill: #6f7a8c;
                -fx-font-size: 15px;
                -fx-font-family: Consolas, monospace;
                -fx-padding: 10 12 10 12;
                -fx-background-radius: 6;
                """);

        Button autoTargetButton = new Button("다음 30분 자동 입력");
        autoTargetButton.setStyle("""
                -fx-background-color: #303849;
                -fx-text-fill: #f5f7fb;
                -fx-font-size: 13px;
                -fx-padding: 10 14 10 14;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                """);
        autoTargetButton.setOnAction(e -> fillNextHalfHourTarget());

        HBox targetInputRow = new HBox(10, targetTimeField, autoTargetButton);
        targetInputRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(targetTimeField, Priority.ALWAYS);

        countdownLabel = new Label("");
        countdownLabel.setMinHeight(38);
        countdownLabel.setStyle("""
                -fx-font-size: 28px;
                -fx-font-weight: bold;
                -fx-text-fill: #7ee787;
                -fx-font-family: Consolas, monospace;
                """);

        clickResultLabel = new Label("");
        clickResultLabel.setWrapText(true);
        clickResultLabel.setStyle(statusStyle("#f5f7fb"));

        startButton = new Button("예약 시작");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setDisable(true);
        startButton.setStyle("""
                -fx-background-color: #1f9d72;
                -fx-text-fill: white;
                -fx-font-size: 15px;
                -fx-font-weight: bold;
                -fx-padding: 12 24 12 24;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                """);
        startButton.setOnAction(e -> startSchedule());

        fillNextHalfHourTarget();

        VBox statusBox = new VBox(10, syncStatusLabel, coordLabel, targetLabel, targetInputRow);
        statusBox.setAlignment(Pos.CENTER_LEFT);

        HBox actionRow = new HBox(startButton);
        HBox.setHgrow(startButton, Priority.ALWAYS);

        VBox root = new VBox(16,
                title,
                subtitle,
                new Separator(),
                timeLabel,
                statusBox,
                countdownLabel,
                clickResultLabel,
                actionRow
        );
        root.setPadding(new Insets(26));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #151922;");

        Scene scene = new Scene(root, 520, 430);
        stage.setTitle("네이버 지도 예약 클릭");
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(430);
        stage.setOnCloseRequest(e -> {
            Platform.exit();
            System.exit(0);
        });
        stage.show();

        initSync();
        startTimeDisplay();
        listenHotkey();
    }

    private void initSync() {
        Thread thread = new Thread(() -> {
            try {
                timeSync.sync(5);
                scheduler = new ClickScheduler(timeSync);
                Platform.runLater(() -> {
                    updateSyncStatus();
                    fillNextHalfHourTarget();
                    syncStatusLabel.setStyle(statusStyle("#7ee787"));
                    updateStartButtonState();
                });
                startContinuousSync();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    syncStatusLabel.setText("동기화 실패: " + e.getMessage());
                    syncStatusLabel.setStyle(statusStyle("#ff6b6b"));
                });
            }
        }, "server-time-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private void startContinuousSync() {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(8000);
                    timeSync.syncBackground();
                    Platform.runLater(this::updateSyncStatus);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        syncStatusLabel.setText("주기 동기화 실패: " + e.getMessage());
                        syncStatusLabel.setStyle(statusStyle("#ff6b6b"));
                    });
                }
            }
        }, "continuous-server-time-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private void startTimeDisplay() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                LocalDateTime t = timeSync.getServerTimeKST();
                long millis = Math.floorMod(timeSync.getServerTimeMillis(), 1000);
                timeLabel.setText(String.format("%02d:%02d:%02d.%03d",
                        t.getHour(), t.getMinute(), t.getSecond(), millis));
            }
        }.start();
    }

    private void listenHotkey() {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    HotkeyCoordMapper mapper = new HotkeyCoordMapper();
                    mapper.waitForHotkey();

                    mappedX = mapper.getMappedX();
                    mappedY = mapper.getMappedY();

                    Platform.runLater(() -> {
                        coordLabel.setText("좌표 설정됨: X = %d, Y = %d".formatted(mappedX, mappedY));
                        coordLabel.setStyle(statusStyle("#7ee787"));
                        updateStartButtonState();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        coordLabel.setText("핫키 등록 실패: " + e.getMessage());
                        coordLabel.setStyle(statusStyle("#ff6b6b"));
                    });
                    return;
                }
            }
        }, "hotkey-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateStartButtonState() {
        startButton.setDisable(scheduler == null || mappedX < 0 || mappedY < 0);
    }

    private void startSchedule() {
        startButton.setDisable(true);
        startButton.setText("예약 대기 중");
        clickResultLabel.setText("");

        Long targetMillis = readTargetMillis();
        if (targetMillis == null) {
            startButton.setText("예약 시작");
            updateStartButtonState();
            return;
        }

        LocalDateTime targetTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMillis), KST);
        targetLabel.setText("목표 시각: %02d:%02d:%02d.000 | 실제 클릭 +%d ms".formatted(
                targetTime.getHour(), targetTime.getMinute(), targetTime.getSecond(),
                scheduler.getSafeDelayMillis()));

        AnimationTimer countdown = new AnimationTimer() {
            @Override
            public void handle(long now) {
                long remaining = targetMillis - timeSync.getServerTimeMillis();
                if (remaining > 0) {
                    long min = remaining / 60000;
                    long sec = (remaining % 60000) / 1000;
                    long ms = remaining % 1000;
                    countdownLabel.setText(String.format("%02d:%02d.%03d", min, sec, ms));
                } else {
                    countdownLabel.setText("00:00.000");
                }
            }
        };
        countdown.start();

        Thread thread = new Thread(() -> {
            try {
                long delta = scheduler.scheduleClickAt(mappedX, mappedY, targetMillis);
                Platform.runLater(() -> {
                    countdown.stop();
                    countdownLabel.setText("");
                    updateSyncStatus();
                    clickResultLabel.setText("클릭 완료: 목표 대비 %+d ms".formatted(delta));
                    clickResultLabel.setStyle(statusStyle("#7ee787"));
                    startButton.setText("예약 시작");
                    updateStartButtonState();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    countdown.stop();
                    clickResultLabel.setText("오류: " + e.getMessage());
                    clickResultLabel.setStyle(statusStyle("#ff6b6b"));
                    startButton.setText("예약 시작");
                    updateStartButtonState();
                });
            }
        }, "click-scheduler");
        thread.setDaemon(true);
        thread.start();
    }

    private static String statusStyle(String color) {
        return "-fx-font-size: 13px; -fx-text-fill: %s;".formatted(color);
    }

    private void fillNextHalfHourTarget() {
        if (scheduler == null) {
            long now = timeSync.getServerTimeMillis();
            long next = ((now / 1000 / 1800) + 1) * 1800 * 1000;
            targetTimeField.setText(formatMillisAsTime(next));
            return;
        }

        targetTimeField.setText(formatMillisAsTime(scheduler.calcNextTargetMillis()));
        targetLabel.setText("3. 목표 시각: 다음 30분 정각으로 설정됨");
    }

    private Long readTargetMillis() {
        String input = targetTimeField.getText().trim();
        try {
            LocalTime targetTime = LocalTime.parse(input, TIME_INPUT_FORMAT);
            return scheduler.calcTargetMillis(targetTime);
        } catch (DateTimeParseException e) {
            clickResultLabel.setText("목표 시각을 HH:mm:ss 형식으로 입력하세요. 예: 15:30:00");
            clickResultLabel.setStyle(statusStyle("#ff6b6b"));
            targetTimeField.requestFocus();
            return null;
        }
    }

    private static String formatMillisAsTime(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), KST);
        return "%02d:%02d:%02d".formatted(t.getHour(), t.getMinute(), t.getSecond());
    }

    private void updateSyncStatus() {
        if (scheduler == null) {
            syncStatusLabel.setText(timeSync.getLastStatus());
            return;
        }
        String updatedAt = timeSync.getLastSyncLocalMillis() > 0
                ? formatMillisAsTime(timeSync.getLastSyncLocalMillis())
                : "--:--:--";
        syncStatusLabel.setText("%s | 안전 지연 %d ms | 갱신 %d회 %s".formatted(
                timeSync.getLastStatus(),
                scheduler.getSafeDelayMillis(),
                timeSync.getSyncCount(),
                updatedAt));
        syncStatusLabel.setStyle(statusStyle("#7ee787"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
