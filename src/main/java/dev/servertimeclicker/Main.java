package dev.servertimeclicker;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main extends Application {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_INPUT_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String TIME_LABEL_STYLE = """
            -fx-font-size: 36px;
            -fx-font-weight: bold;
            -fx-text-fill: %s;
            -fx-font-family: Consolas, monospace;
            """;
    private static final String COORD_GUIDE_TEXT =
            "2. 좌표 지정: 클릭할 버튼 위에 마우스를 올리고 Ctrl + F1을 누르세요.";

    private final ServerTimeSync timeSync = new ServerTimeSync();
    private final AtomicBoolean continuousSyncStarted = new AtomicBoolean(false);
    private ClickScheduler scheduler;

    private Label timeLabel;
    private Label syncStatusLabel;
    private Label coordLabel;
    private Label countdownLabel;
    private Label clickResultLabel;
    private Label targetLabel;
    private Button startButton;
    private Button applyUrlButton;
    private Button resetCoordButton;
    private Button undoCoordButton;
    private TextField targetTimeField;
    private TextField urlField;
    private TextField intervalField;
    private ListView<Point> coordListView;

    /** 클릭할 좌표를 지정한 순서대로 담는다. FX 스레드에서만 수정한다. */
    private final ObservableList<Point> coords = FXCollections.observableArrayList();

    @Override
    public void start(Stage stage) {
        Label title = new Label("서버 시간 예약 클릭");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #f5f7fb;");

        Label subtitle = new Label("기준 사이트를 정하고 좌표를 지정한 뒤 목표 시각을 확인하고 예약 시작을 누르면 됩니다.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca8ba;");

        timeLabel = new Label("동기화 중...");
        timeLabel.setMinWidth(320);
        timeLabel.setAlignment(Pos.CENTER);
        timeLabel.setStyle(TIME_LABEL_STYLE.formatted("#6f7a8c"));

        urlField = new TextField(timeSync.getTargetUrl());
        urlField.setPromptText("예: https://map.naver.com");
        urlField.setStyle(inputStyle());

        applyUrlButton = new Button("적용");
        applyUrlButton.setStyle("""
                -fx-background-color: #303849;
                -fx-text-fill: #f5f7fb;
                -fx-font-size: 13px;
                -fx-padding: 10 14 10 14;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                """);
        applyUrlButton.setOnAction(e -> applyTargetUrl());
        urlField.setOnAction(e -> applyTargetUrl());

        HBox urlRow = new HBox(10, urlField, applyUrlButton);
        urlRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(urlField, Priority.ALWAYS);

        CheckBox insecureTlsCheck = new CheckBox("인증서 검증 완화 (신뢰할 수 있는 사이트에서만 사용)");
        insecureTlsCheck.setStyle(statusStyle("#ffd166"));
        insecureTlsCheck.setSelected(timeSync.isAllowInsecureTls());
        insecureTlsCheck.selectedProperty().addListener((obs, oldVal, newVal) ->
                timeSync.setAllowInsecureTls(newVal));

        syncStatusLabel = new Label("1. 기준 사이트의 서버 시간 동기화 중");
        syncStatusLabel.setStyle(statusStyle("#c4cad4"));

        Label urlLabel = new Label("0. 기준 사이트: 시간을 맞출 웹페이지 주소");
        urlLabel.setStyle(statusStyle("#c4cad4"));

        coordLabel = new Label(COORD_GUIDE_TEXT);
        coordLabel.setWrapText(true);
        coordLabel.setStyle(statusStyle("#ffd166"));

        coordListView = new ListView<>(coords);
        coordListView.setPrefHeight(96);
        coordListView.setPlaceholder(new Label("지정된 좌표가 없습니다."));
        coordListView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Point point, boolean empty) {
                super.updateItem(point, empty);
                if (empty || point == null) {
                    setText(null);
                } else {
                    setText("%d. %s".formatted(getIndex() + 1, point));
                }
                setStyle("-fx-background-color: transparent; -fx-text-fill: #f5f7fb;");
            }
        });
        coordListView.setStyle("""
                -fx-background-color: #222936;
                -fx-control-inner-background: #222936;
                -fx-font-size: 13px;
                -fx-font-family: Consolas, monospace;
                -fx-background-radius: 6;
                """);

        undoCoordButton = new Button("마지막 취소");
        undoCoordButton.setStyle(smallButtonStyle());
        undoCoordButton.setOnAction(e -> undoLastCoord());

        resetCoordButton = new Button("좌표 초기화");
        resetCoordButton.setStyle(smallButtonStyle());
        resetCoordButton.setOnAction(e -> resetCoord());

        Label intervalCaption = new Label("클릭 간격");
        intervalCaption.setStyle(statusStyle("#c4cad4"));

        intervalField = new TextField(String.valueOf(ClickScheduler.DEFAULT_INTERVAL_MILLIS));
        intervalField.setPrefWidth(70);
        intervalField.setStyle(inputStyle());

        Label intervalUnit = new Label("ms");
        intervalUnit.setStyle(statusStyle("#c4cad4"));

        HBox coordButtonRow = new HBox(10,
                undoCoordButton, resetCoordButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                intervalCaption, intervalField, intervalUnit);
        coordButtonRow.setAlignment(Pos.CENTER_LEFT);

        VBox coordBox = new VBox(8, coordLabel, coordListView, coordButtonRow);

        targetLabel = new Label("3. 목표 시각: 다음 30분 정각 또는 직접 입력");
        targetLabel.setStyle(statusStyle("#c4cad4"));

        targetTimeField = new TextField();
        targetTimeField.setPromptText("예: 15:30:00");
        targetTimeField.setText(formatMillisAsTime(timeSync.getServerTimeMillis()));
        targetTimeField.setStyle(inputStyle());

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
        updateCoordLabel();

        VBox statusBox = new VBox(10,
                urlLabel, urlRow, insecureTlsCheck, syncStatusLabel, coordBox, targetLabel, targetInputRow);
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

        Scene scene = new Scene(root, 560, 720);
        stage.setTitle("서버 시간 예약 클릭");
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(720);
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

    private void applyTargetUrl() {
        String input = urlField.getText();
        applyUrlButton.setDisable(true);
        startButton.setDisable(true);
        syncStatusLabel.setText("기준 사이트 확인 중...");
        syncStatusLabel.setStyle(statusStyle("#c4cad4"));

        Thread thread = new Thread(() -> {
            try {
                timeSync.setTargetUrl(input);
                timeSync.sync(5);
                Platform.runLater(() -> {
                    urlField.setText(timeSync.getTargetUrl());
                    if (scheduler == null) {
                        scheduler = new ClickScheduler(timeSync);
                    }
                    updateSyncStatus();
                    fillNextHalfHourTarget();
                    applyUrlButton.setDisable(false);
                    updateStartButtonState();
                });
                startContinuousSync();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String message = e.getMessage();
                    if (ServerTimeSync.isCertificateChainError(e)) {
                        message = "인증서 검증에 실패했습니다. 신뢰할 수 있는 사이트라면 "
                                + "'인증서 검증 완화'를 켠 뒤 다시 시도하세요.";
                    }
                    syncStatusLabel.setText(message);
                    syncStatusLabel.setStyle(statusStyle("#ff6b6b"));
                    applyUrlButton.setDisable(false);
                    updateStartButtonState();
                });
            }
        }, "target-url-apply");
        thread.setDaemon(true);
        thread.start();
    }

    private void startContinuousSync() {
        if (!continuousSyncStarted.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(8000);
                    // 정밀 동기화~클릭 임계 구간에는 주기 동기화가 offset을 덮어쓰지 않게 건너뛴다.
                    if (timeSync.isBackgroundSyncPaused()) {
                        continue;
                    }
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

                // 동기화 전에는 서버 시간이 아니라는 걸 시계 색으로 드러낸다.
                timeLabel.setStyle(timeSync.isSyncedForTarget()
                        ? TIME_LABEL_STYLE.formatted("#20c7b5")
                        : TIME_LABEL_STYLE.formatted("#6f7a8c"));
            }
        }.start();
    }

    private void listenHotkey() {
        try {
            new HotkeyCoordMapper(this::addCoord).register();
        } catch (Exception e) {
            coordLabel.setText("핫키 등록 실패: " + e.getMessage());
            coordLabel.setStyle(statusStyle("#ff6b6b"));
        }
    }

    /** 핫키 스레드에서 호출되므로 FX 스레드로 넘겨 목록을 수정한다. */
    private void addCoord(Point point) {
        Platform.runLater(() -> {
            coords.add(point);
            coordListView.scrollTo(coords.size() - 1);
            updateCoordLabel();
            updateStartButtonState();
        });
    }

    private void resetCoord() {
        coords.clear();
        updateCoordLabel();
        updateStartButtonState();
    }

    private void undoLastCoord() {
        if (!coords.isEmpty()) {
            coords.remove(coords.size() - 1);
        }
        updateCoordLabel();
        updateStartButtonState();
    }

    private void updateCoordLabel() {
        boolean empty = coords.isEmpty();
        undoCoordButton.setDisable(empty);
        resetCoordButton.setDisable(empty);

        if (empty) {
            coordLabel.setText(COORD_GUIDE_TEXT);
            coordLabel.setStyle(statusStyle("#ffd166"));
        } else {
            coordLabel.setText("2. 좌표 %d개 지정됨. 위에서부터 순서대로 클릭합니다. (Ctrl + F1로 추가)"
                    .formatted(coords.size()));
            coordLabel.setStyle(statusStyle("#7ee787"));
        }
    }

    private void updateStartButtonState() {
        // 현재 URL로 동기화되지 않았다면 시계가 내 PC 시간이거나 다른 사이트 기준이므로 막는다.
        startButton.setDisable(
                scheduler == null || !timeSync.isSyncedForTarget() || coords.isEmpty());
    }

    private void startSchedule() {
        startButton.setDisable(true);
        startButton.setText("예약 대기 중");
        clickResultLabel.setText("");

        Long targetMillis = readTargetMillis();
        Long intervalMillis = readIntervalMillis();
        if (targetMillis == null || intervalMillis == null) {
            startButton.setText("예약 시작");
            updateStartButtonState();
            return;
        }

        LocalDateTime targetTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMillis), KST);
        targetLabel.setText("목표 시각: %02d:%02d:%02d.000 | 실제 클릭 +%d ms | 좌표 %d개".formatted(
                targetTime.getHour(), targetTime.getMinute(), targetTime.getSecond(),
                scheduler.getSafeDelayMillis(), coords.size()));

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

        // 예약 시점의 좌표와 간격을 고정한다. 대기 중 목록을 바꿔도 이미 잡힌 예약은 그대로 진행된다.
        List<Point> clickPoints = new ArrayList<>(coords);

        Thread thread = new Thread(() -> {
            try {
                long delta = scheduler.scheduleClicksAt(clickPoints, targetMillis, intervalMillis);
                Platform.runLater(() -> {
                    countdown.stop();
                    countdownLabel.setText("");
                    updateSyncStatus();
                    clickResultLabel.setText("클릭 %d회 완료: 첫 클릭 목표 대비 %+d ms"
                            .formatted(clickPoints.size(), delta));
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

    private static String smallButtonStyle() {
        return """
                -fx-background-color: #303849;
                -fx-text-fill: #f5f7fb;
                -fx-font-size: 13px;
                -fx-padding: 8 12 8 12;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                """;
    }

    private static String inputStyle() {
        return """
                -fx-background-color: #222936;
                -fx-text-fill: #f5f7fb;
                -fx-prompt-text-fill: #6f7a8c;
                -fx-font-size: 15px;
                -fx-font-family: Consolas, monospace;
                -fx-padding: 10 12 10 12;
                -fx-background-radius: 6;
                """;
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

    /** 클릭 간격을 읽는다. 값이 잘못되면 오류를 표시하고 null을 돌려준다. */
    private Long readIntervalMillis() {
        String input = intervalField.getText().trim();
        try {
            long interval = Long.parseLong(input);
            if (interval < ClickScheduler.MIN_INTERVAL_MILLIS
                    || interval > ClickScheduler.MAX_INTERVAL_MILLIS) {
                throw new NumberFormatException();
            }
            return interval;
        } catch (NumberFormatException e) {
            clickResultLabel.setText("클릭 간격은 %d ~ %d ms 사이의 숫자로 입력하세요.".formatted(
                    ClickScheduler.MIN_INTERVAL_MILLIS, ClickScheduler.MAX_INTERVAL_MILLIS));
            clickResultLabel.setStyle(statusStyle("#ff6b6b"));
            intervalField.requestFocus();
            return null;
        }
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
        syncStatusLabel.setStyle(statusStyle(timeSync.isSyncedForTarget() ? "#7ee787" : "#ffd166"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
