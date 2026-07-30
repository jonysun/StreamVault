package com.flower.spirit.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class ControlledProcessExecutorTest {

    private final ControlledProcessExecutor executor = new ControlledProcessExecutor();

    @Test
    void capturesBothStreamsAndExitCode() throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        ControlledProcessExecutor.Result result = executor.execute(
                List.of(java, "-version"), Duration.ofSeconds(10), "java-version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("version") || result.stdout().contains("version"));
        assertTrue(result.duration().toMillis() >= 0);
    }

    @Test
    void terminatesAProcessThatExceedsItsDeadline() throws Exception {
        List<String> command = isWindows()
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 10")
                : List.of("sh", "-c", "sleep 10");

        ControlledProcessExecutor.Result result = executor.execute(
                command, Duration.ofMillis(100), "deadline-test");

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
