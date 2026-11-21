package io.minclash.app;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RoutingMutationsCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("hold")) {
            RoutingMutations.run(new File(args[1]), () -> {
                System.out.println("locked");
                System.in.read();
                return null;
            });
            return;
        }
        File lock = Files.createTempFile(new File(args[0]).toPath(), "routing-mutations-", ".lock").toFile();
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        Process owner = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            RoutingMutationsCheck.class.getName(), "hold", lock.getAbsolutePath()).redirectErrorStream(true).start();
        try {
            FutureTask<String> ready = new FutureTask<>(() -> owner.inputReader().readLine());
            new Thread(ready).start();
            require("locked".equals(ready.get(5, TimeUnit.SECONDS)), "child must lock before the contender starts");
            FutureTask<String> contender = new FutureTask<>(() -> RoutingMutations.run(lock,
                () -> RoutingMutations.run(lock, () -> "entered")));
            Thread worker = new Thread(contender);
            worker.setDaemon(true);
            worker.start();
            try {
                contender.get(250, TimeUnit.MILLISECONDS);
                throw new AssertionError("configuration transactions overlapped across processes");
            } catch (TimeoutException expected) { }
            owner.destroyForcibly();
            require(owner.waitFor(5, TimeUnit.SECONDS), "owner should exit");
            require("entered".equals(contender.get(5, TimeUnit.SECONDS)), "owner death must release the lock");
        } finally {
            if (owner.isAlive()) owner.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(lock.toPath());
        }
        System.out.println("PASS: cross-process exclusion, reentrancy, and release on process exit.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
