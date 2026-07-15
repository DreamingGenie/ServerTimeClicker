package dev.servertimeclicker;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * JavaFX를 클래스패스(비-모듈)로 실행할 때, main 클래스가 Application을 상속하면
 * "JavaFX runtime components are missing" 오류가 발생한다.
 * Application을 상속하지 않는 이 런처를 진입점으로 사용하면 그 검사를 우회할 수 있다.
 */
public class Launcher {

    /**
     * jnativehook 네이티브 DLL(JNativeHook.dll)은 VCRUNTIME140.dll에 의존한다.
     * 갓 포맷한 Windows에는 Visual C++ 재배포 런타임이 없어서 이 DLL이 없고,
     * 그 결과 핫키 등록 시 UnsatisfiedLinkError가 발생한다.
     * JavaFX와 달리 jnativehook은 VC 런타임을 함께 풀지 않으므로, 여기서 우리가
     * 번들한 VC 런타임 DLL을 미리(preload) 메모리에 올려두면 jnativehook DLL의
     * 임포트가 이미 로드된 모듈로 해결되어 정상 동작한다.
     */
    private static final String[] VC_RUNTIME_DLLS = {
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "msvcp140.dll",
    };

    public static void main(String[] args) {
        Path nativeDir = prepareWritableNativeDir();
        preloadVcRuntime(nativeDir);
        Main.main(args);
    }

    /**
     * jnativehook은 기본적으로 JAR가 있는 폴더에 네이티브 DLL을 추출한다.
     * 설치형(exe)에서는 그 폴더가 "C:\Program Files\...\app" 이라 쓰기가 거부되어
     * UnsatisfiedLinkError(액세스 거부)가 발생한다. 쓰기 가능한 사용자 폴더를
     * jnativehook.lib.path 로 지정해 DLL을 그곳에 풀도록 한다.
     */
    private static Path prepareWritableNativeDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getProperty("java.io.tmpdir");
        }
        try {
            Path dir = Paths.get(base, "ServerTimeClicker", "native");
            Files.createDirectories(dir);
            System.setProperty("jnativehook.lib.path", dir.toString());
            return dir;
        } catch (Throwable t) {
            try {
                Path dir = Files.createTempDirectory("nmc-native");
                System.setProperty("jnativehook.lib.path", dir.toString());
                return dir;
            } catch (Throwable t2) {
                System.err.println("네이티브 폴더 준비 실패: " + t2.getMessage());
                return null;
            }
        }
    }

    private static void preloadVcRuntime(Path nativeDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win") || nativeDir == null) {
            return;
        }
        try {
            for (String dll : VC_RUNTIME_DLLS) {
                String resource = "/native/win/x86_64/" + dll;
                try (InputStream in = Launcher.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        continue;
                    }
                    Path target = nativeDir.resolve(dll);
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        System.load(target.toAbsolutePath().toString());
                    } catch (Throwable t) {
                        // 이미 시스템에 설치돼 있거나 로드 불가한 경우 무시하고 진행한다.
                        System.err.println("VC 런타임 preload 건너뜀(" + dll + "): " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("VC 런타임 preload 실패: " + t.getMessage());
        }
    }
}
