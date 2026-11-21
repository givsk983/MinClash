package io.minclash.app;

import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Durable intent for the update of the Root profile and the private routing state. */
final class ProfileApplyJournal {
    private final AtomicFile file;

    ProfileApplyJournal(File directory) {
        file = new AtomicFile(new File(directory, "routing-apply.pending"));
    }

    record Pending(String oldProfile, String newProfile, String oldRouting, byte[] newRouting, String liveToken) {
        void write(OutputStream stream) throws IOException {
            if (newRouting.length == 0 || newRouting.length > BackupArchive.MAX_BYTES) {
                throw new IOException("待应用的分流设置大小无效。");
            }
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(1);
            output.writeUTF(oldProfile);
            output.writeUTF(newProfile);
            output.writeUTF(oldRouting);
            output.writeUTF(liveToken);
            output.writeInt(newRouting.length);
            output.write(newRouting);
        }

        static Pending read(InputStream stream) throws IOException {
            DataInputStream input = new DataInputStream(stream);
            if (input.readInt() != 1) throw new IOException("配置恢复记录的版本无效。");
            String oldProfile = input.readUTF();
            String newProfile = input.readUTF();
            String oldRouting = input.readUTF();
            String liveToken = input.readUTF();
            int size = input.readInt();
            if (!validDigest(oldProfile) || !newProfile.matches("[0-9a-f]{64}") || !validDigest(oldRouting)
                || !liveToken.matches("(?:[0-9a-f]{32})?") || size <= 0 || size > BackupArchive.MAX_BYTES) {
                throw new IOException("配置恢复记录无效。");
            }
            byte[] newRouting = new byte[size];
            input.readFully(newRouting);
            if (input.read() != -1) throw new IOException("配置恢复记录包含多余数据。");
            return new Pending(oldProfile, newProfile, oldRouting, newRouting, liveToken);
        }

        Resolution resolve(LiveProfile.RecoveryState root, byte[] installedRouting) throws Exception {
            String routing = digest(installedRouting);
            String candidate = digest(newRouting);
            boolean old = routing.equals(oldRouting);
            boolean committed = routing.equals(candidate);
            if (!old && !committed) return Resolution.CONFLICT;
            if (root.profile().equals(newProfile)) return committed ? Resolution.CLEAR : Resolution.COMMIT;
            if (!root.profile().equals(oldProfile)) return Resolution.CONFLICT;
            if (!liveToken.isEmpty() && root.liveUncertain()) return Resolution.STOP_REQUIRED;
            return old ? Resolution.CLEAR : Resolution.CONFLICT;
        }
    }

    enum Resolution { CLEAR, COMMIT, CONFLICT, STOP_REQUIRED }

    static String digest(byte[] content) throws Exception {
        return content == null ? "-" : Module.profileDigest(content);
    }

    private static boolean validDigest(String value) { return value.equals("-") || value.matches("[0-9a-f]{64}"); }

    Pending read() throws IOException {
        try (InputStream stream = file.openRead()) { return Pending.read(stream); }
        catch (FileNotFoundException missing) {
            if (file.getBaseFile().exists()) throw missing;
            return null;
        }
    }

    void write(Pending pending) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            pending.write(output);
            output.getFD().sync();
        } catch (IOException | RuntimeException error) {
            file.failWrite(output);
            throw error;
        }
        file.finishWrite(output);
        Pending written = read();
        if (written == null || !pending.newProfile().equals(written.newProfile())
            || !java.util.Arrays.equals(pending.newRouting(), written.newRouting())) {
            throw new IOException("无法保存配置恢复记录。");
        }
    }

    void clear() throws IOException {
        file.delete();
        if (file.getBaseFile().exists()) throw new IOException("无法清理配置恢复记录。");
    }
}
