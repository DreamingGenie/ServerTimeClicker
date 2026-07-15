package dev.servertimeclicker;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.awt.MouseInfo;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ctrl + F1을 누를 때마다 현재 마우스 좌표를 콜백으로 넘긴다.
 *
 * <p>네이티브 훅은 앱 시작 시 한 번만 등록하고 계속 유지한다. 좌표를 잡을 때마다 훅을
 * 해제하고 다시 등록하면 훅 스레드가 내려가기 전에 재등록이 겹쳐 조용히 멈추기 때문에,
 * 두 번째 좌표부터 입력을 받지 못한다.
 */
public class HotkeyCoordMapper implements NativeKeyListener {
    /** 키를 누르고 있을 때 발생하는 자동 반복으로 같은 좌표가 연달아 들어오는 것을 막는다. */
    private static final long REPEAT_GUARD_MILLIS = 400;

    private final Consumer<Point> onCapture;
    private volatile long lastCaptureMillis = 0;

    public HotkeyCoordMapper(Consumer<Point> onCapture) {
        this.onCapture = onCapture;
    }

    public void register() throws NativeHookException {
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);

        if (!GlobalScreen.isNativeHookRegistered()) {
            GlobalScreen.registerNativeHook();
        }
        GlobalScreen.addNativeKeyListener(this);
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        boolean isCtrl = (e.getModifiers() & NativeKeyEvent.CTRL_MASK) != 0;
        boolean isF1 = e.getKeyCode() == NativeKeyEvent.VC_F1;
        if (!isCtrl || !isF1) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCaptureMillis < REPEAT_GUARD_MILLIS) {
            return;
        }
        lastCaptureMillis = now;

        java.awt.Point mouse = MouseInfo.getPointerInfo().getLocation();
        onCapture.accept(new Point(mouse.x, mouse.y));
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
    }
}
