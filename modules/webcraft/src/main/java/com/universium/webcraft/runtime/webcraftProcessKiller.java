package com.universium.webcraft.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class webcraftProcessKiller {
    private static final Logger LOGGER = LoggerFactory.getLogger(webcraftProcessKiller.class);
    private static final List<String> COMMAND_MARKERS = List.of(
            "net.neoforged.devlaunch.main",
            "serverrunprogramargs.txt",
            "forgeserverdev",
            "serverrunvmargs.txt",
            "fml.modfolders=webcraft",
            "modules\\webcraft\\run\\world",
            "modules/webcraft/run/world"
    );

    private webcraftProcessKiller() {
    }

    public static int killMatchingDevProcesses(boolean enabled) {
        if (!enabled) {
            return 0;
        }

        long currentPid = ProcessHandle.current().pid();
        int killed = 0;

        for (ProcessHandle processHandle : ProcessHandle.allProcesses().toList()) {
            if (processHandle.pid() == currentPid) {
                continue;
            }

            Optional<String> commandLine = processHandle.info().commandLine();
            if (commandLine.isEmpty()) {
                continue;
            }

            String normalized = commandLine.get().toLowerCase(Locale.ROOT);
            if (!looksLikeWebcraftDevProcess(normalized)) {
                continue;
            }

            LOGGER.info("webcraft killing stale process {}: {}", processHandle.pid(), normalized);
            processHandle.destroy();
            if (processHandle.isAlive()) {
                processHandle.destroyForcibly();
            }
            killed++;
        }

        return killed;
    }

    private static boolean looksLikeWebcraftDevProcess(String commandLine) {
        for (String marker : COMMAND_MARKERS) {
            if (commandLine.contains(marker)) {
                return true;
            }
        }
        return commandLine.contains("modules\\webcraft\\build\\moddev")
                || commandLine.contains("modules/webcraft/build/moddev")
                || commandLine.contains("webcraft")
                || commandLine.contains("--gameDir")
                || commandLine.contains("serverrunprogramargs");
    }
}
