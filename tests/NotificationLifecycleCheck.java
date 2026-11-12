package io.minclash.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class NotificationLifecycleCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("writer")) {
            NotificationPreference setting = new NotificationPreference(new File(args[1]));
            for (int i = 0; i < 100; i++) setting.setEnabled(i % 2 == 0);
            setting.setEnabled(false);
            return;
        }
        durablePreference();
        sessionLifetime();
        System.out.println("PASS: legacy opt-in, atomic cross-process preference, failed writes, restart/stop grace.");
    }

    private static void durablePreference() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"), "notification-check-");
        Path published = directory.resolve("notification-control");
        Path pending = directory.resolve("notification-control.tmp");
        Process writer = null;
        try {
            NotificationPreference reader = new NotificationPreference(directory.toFile());
            require(reader.enabled(true) && !reader.enabled(false), "upgrade preserves the legacy choice");
            reader.setEnabled(true);
            require(new NotificationPreference(directory.toFile()).enabled(false), "opt-in survives recreation");
            Files.writeString(pending, "0");
            require(reader.enabled(false), "an interrupted unpublished write cannot change the setting");
            Files.delete(pending);
            Files.createDirectory(pending);
            boolean failed = false;
            try { reader.setEnabled(false); }
            catch (IOException expected) { failed = true; }
            require(failed && reader.enabled(false), "failed save preserves the last published setting");

            String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            writer = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                NotificationLifecycleCheck.class.getName(), "writer", directory.toString()).inheritIO().start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (writer.isAlive() && System.nanoTime() < deadline) {
                // Reuse the same reader while another process publishes toggles.
                reader.enabled(true);
                Thread.sleep(1);
            }
            require(writer.waitFor(1, TimeUnit.SECONDS) && writer.exitValue() == 0, "independent writer completes");
            require(!reader.enabled(true), "disabled setting overrides a stale legacy opt-in across processes");
            Files.writeString(published, "broken");
            boolean invalid = false;
            try { reader.enabled(true); }
            catch (IOException expected) { invalid = true; }
            require(invalid, "corruption must not silently restore a stale opt-in");
        } finally {
            if (writer != null && writer.isAlive()) {
                writer.destroyForcibly();
                writer.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(pending);
            Files.deleteIfExists(published);
            Files.delete(directory);
        }
    }

    private static void sessionLifetime() {
        NotificationLifetime session = new NotificationLifetime();
        require(!session.shouldStop(true, 0), "first stopped sample does not remove controls");
        for (int time = 0; time < 30000; time += 3000) {
            require(!session.shouldStop(true, time), "cached stopped samples respect the time grace");
        }
        require(!session.shouldStop(false, 29000), "active or unavailable status resets stop confirmation");
        require(!session.shouldStop(true, 30000) && !session.shouldStop(true, 59999), "restart gets a new grace");
        require(session.shouldStop(true, 60000), "sustained stop eventually ends the foreground service");

        session = new NotificationLifetime();
        session.restoring(1000);
        require(!session.shouldStop(true, 1000) && !session.shouldStop(true, 180999),
            "boot and sticky recreation allow the module time to start");
        require(session.shouldStop(true, 181000), "restoration grace is bounded");
        require(!session.shouldStop(false, 181001), "network recovery retains controls");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
