package dev.naverclicker;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.awt.MouseInfo;
import java.awt.Point;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HotkeyCoordMapper implements NativeKeyListener {
    private static final Object HOOK_LOCK = new Object();

    private int mappedX = -1;
    private int mappedY = -1;
    private CountDownLatch latch;

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        boolean isCtrl = (e.getModifiers() & NativeKeyEvent.CTRL_MASK) != 0;
        boolean isF1 = e.getKeyCode() == NativeKeyEvent.VC_F1;

        if (isCtrl && isF1 && latch != null) {
            Point mousePos = MouseInfo.getPointerInfo().getLocation();
            mappedX = mousePos.x;
            mappedY = mousePos.y;
            latch.countDown();
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
    }

    public void waitForHotkey() throws NativeHookException, InterruptedException {
        latch = new CountDownLatch(1);
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);

        synchronized (HOOK_LOCK) {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
        }

        try {
            latch.await();
        } finally {
            synchronized (HOOK_LOCK) {
                GlobalScreen.removeNativeKeyListener(this);
                GlobalScreen.unregisterNativeHook();
            }
        }
    }

    public int getMappedX() {
        return mappedX;
    }

    public int getMappedY() {
        return mappedY;
    }
}
