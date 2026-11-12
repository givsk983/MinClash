package io.minclash.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** One UI writer, uncached readers in both processes. Publish only a fully synced value. */
final class NotificationPreference {
    private final File file;

    NotificationPreference(File directory) { file = new File(directory, "notification-control"); }

    boolean enabled(boolean legacy) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            int value = input.read();
            if ((value != '0' && value != '1') || input.read() != -1) {
                throw new IOException("Invalid notification preference");
            }
            return value == '1';
        } catch (FileNotFoundException missing) {
            if (file.exists()) throw missing;
            return legacy;
        }
    }

    synchronized void setEnabled(boolean enabled) throws IOException {
        File pending = new File(file.getPath() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(enabled ? '1' : '0');
                output.getFD().sync();
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
    }
}
