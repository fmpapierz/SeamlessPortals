package testclient;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Keeps an unattended test client moving and lets a script drive it without keyboard or mouse input.
 * <p>
 * Every half second it answers the startup prompts that would otherwise stall a minimized client:
 * <ul>
 * <li>the first-start accessibility/narrator onboarding screen: "Continue" ({@code onClose()}), never Enter, which
 * would turn the narrator on;</li>
 * <li>the "Experimental Settings" warning: "I know what I'm doing!" on the backup screen shown when a world with mod
 * data registries is opened, "Proceed" on the world-creation ConfirmScreen. Only screens whose title mentions
 * Experimental are answered, so no other confirmation is ever touched.</li>
 * </ul>
 * It also watches {@code <game dir>/testclient/inbox} for {@code *.txt} command files (processed in name order, then
 * deleted), one command per line:
 * <ul>
 * <li>{@code screenshot} saves a screenshot from the main render target into {@code screenshots/};</li>
 * <li>{@code cmd <command>} runs a command as the local player;</li>
 * <li>{@code shoot <count>} fires the gun in the main hand that many times, through TACZ's own client API (the call
 * its shoot key makes; the key itself only works in a focused window), and prints each {@code ShootResult};</li>
 * <li>{@code quit} shuts the game down cleanly (saving the world).</li>
 * </ul>
 * Minecraft 26.x is not obfuscated, so everything goes through reflection by Mojang names on every loader, in
 * development and in production alike.
 */
final class RemoteControl implements Runnable {
    private final Instrumentation instrumentation;
    /** The prompt last answered, so one screen is never answered twice (proceeding twice would load a world twice). */
    private Object answered;
    private String lastScreen = "";

    RemoteControl(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    static void start(Instrumentation instrumentation) {
        Thread thread = new Thread(new RemoteControl(instrumentation), "testclient-remote");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        Path inbox = Path.of("testclient", "inbox");
        Class<?> minecraft = null;
        int ticks = 0;
        while (true) {
            try {
                Thread.sleep(500);
                ticks++;
                if (minecraft == null && ticks % 4 == 0) {
                    minecraft = findLoadedClass("net.minecraft.client.Minecraft");
                }
                if (minecraft == null) {
                    continue;
                }
                answerPrompts(minecraft);
                if (!Files.isDirectory(inbox)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> listing = Files.list(inbox)) {
                    files = listing.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
                }
                for (Path file : files) {
                    List<String> lines = Files.readAllLines(file);
                    Files.delete(file);
                    for (String line : lines) {
                        handle(minecraft, line.trim());
                    }
                }
            } catch (Throwable t) {
                System.err.println("[testclient] remote control: " + t);
            }
        }
    }

    private static Object instance(Class<?> minecraftClass) throws Exception {
        return minecraftClass.getMethod("getInstance").invoke(null);
    }

    /** The open screen: Minecraft.screen up to 26.1, Minecraft.gui.screen() from 26.2 on. */
    private static Object currentScreen(Class<?> minecraftClass, Object minecraft) throws Exception {
        try {
            return minecraftClass.getField("screen").get(minecraft);
        } catch (NoSuchFieldException e) {
            Object gui = minecraftClass.getField("gui").get(minecraft);
            // null while Minecraft is still being constructed
            return gui == null ? null : gui.getClass().getMethod("screen").invoke(gui);
        }
    }

    private void answerPrompts(Class<?> minecraftClass) throws Exception {
        Object minecraft = instance(minecraftClass);
        if (minecraft == null) {
            return;
        }
        Object screen = currentScreen(minecraftClass, minecraft);
        String screenName = screen == null ? "none" : screen.getClass().getName();
        if (!screenName.equals(lastScreen)) {
            lastScreen = screenName;
            System.out.println("[testclient] screen: " + screenName);
        }
        if (screen == null || screen == answered) {
            return;
        }
        Class<?> screenBase = findSuper(screen.getClass(), "net.minecraft.client.gui.screens.Screen");
        if (screenBase == null) {
            return;
        }
        openPackage(screenBase);
        Method execute = minecraftClass.getMethod("execute", Runnable.class);

        if (findSuper(screen.getClass(), "net.minecraft.client.gui.screens.AccessibilityOnboardingScreen") != null) {
            answered = screen;
            System.out.println("[testclient] continuing past the accessibility/narrator onboarding screen");
            Method onClose = screenBase.getMethod("onClose");
            execute.invoke(minecraft, (Runnable) () -> invokeQuietly(onClose, screen));
            return;
        }

        Field titleField = screenBase.getDeclaredField("title");
        titleField.setAccessible(true);
        Object title = titleField.get(screen);
        // getString() implements Brigadier's Message, so it is public API on every loader
        String text = title == null ? "" : String.valueOf(title.getClass().getMethod("getString").invoke(title));
        if (!text.contains("Experimental")) {
            return;
        }
        Class<?> backup = findSuper(screen.getClass(), "net.minecraft.client.gui.screens.BackupConfirmScreen");
        Class<?> confirm = findSuper(screen.getClass(), "net.minecraft.client.gui.screens.ConfirmScreen");
        if (backup != null) {
            openPackage(backup);
            Field field = backup.getDeclaredField("onProceed");
            field.setAccessible(true);
            Object listener = field.get(screen);
            // Listener.proceed(boolean backup, boolean eraseCache), called through the public interface because
            // the implementation is a lambda in a closed package
            Method proceed = field.getType().getMethod("proceed", boolean.class, boolean.class);
            answered = screen;
            System.out.println("[testclient] accepting \"" + text + "\" (load without backup)");
            execute.invoke(minecraft, (Runnable) () -> invokeQuietly(proceed, listener, false, false));
        } else if (confirm != null) {
            openPackage(confirm);
            Field field = confirm.getDeclaredField("callback");
            field.setAccessible(true);
            Object callback = field.get(screen);
            answered = screen;
            System.out.println("[testclient] accepting \"" + text + "\"");
            Method accept = field.getType().getMethod("accept", boolean.class);
            execute.invoke(minecraft, (Runnable) () -> invokeQuietly(accept, callback, true));
        }
    }

    private static void invokeQuietly(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (Throwable t) {
            System.err.println("[testclient] " + method.getName() + " failed: " + t);
        }
    }

    /** The first class in the hierarchy with that name. */
    private static Class<?> findSuper(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(name)) {
                return k;
            }
        }
        return null;
    }

    /** Opens the game package to this agent, in case the loader put Minecraft in a named module. */
    private void openPackage(Class<?> gameClass) {
        Module module = gameClass.getModule();
        if (module.isNamed() && !module.isOpen(gameClass.getPackageName(), RemoteControl.class.getModule())) {
            instrumentation.redefineModule(module, Set.of(), Map.of(),
                    Map.of(gameClass.getPackageName(), Set.of(RemoteControl.class.getModule())), Set.of(), Map.of());
        }
    }

    private Class<?> findLoadedClass(String name) {
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private void handle(Class<?> minecraftClass, String line) throws Exception {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        Object minecraft = instance(minecraftClass);
        Method execute = minecraftClass.getMethod("execute", Runnable.class);
        ClassLoader gameLoader = minecraftClass.getClassLoader();
        if (line.equals("quit")) {
            Method stop = minecraftClass.getMethod("stop");
            System.out.println("[testclient] quitting");
            execute.invoke(minecraft, (Runnable) () -> invokeQuietly(stop, minecraft));
        } else if (line.equals("screenshot")) {
            execute.invoke(minecraft, (Runnable) () -> {
                try {
                    Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot", true, gameLoader);
                    Class<?> renderTarget = Class.forName("com.mojang.blaze3d.pipeline.RenderTarget", true, gameLoader);
                    File gameDirectory = (File) minecraftClass.getField("gameDirectory").get(minecraft);
                    Object target = minecraftClass.getMethod("getMainRenderTarget").invoke(minecraft);
                    Consumer<Object> done = message -> System.out.println("[testclient] screenshot saved");
                    screenshot.getMethod("grab", File.class, renderTarget, Consumer.class).invoke(null, gameDirectory, target, done);
                } catch (Throwable t) {
                    System.err.println("[testclient] screenshot failed: " + t);
                }
            });
        } else if (line.startsWith("cmd ")) {
            String command = line.substring("cmd ".length()).trim();
            execute.invoke(minecraft, (Runnable) () -> {
                try {
                    Object player = minecraftClass.getField("player").get(minecraft);
                    if (player == null) {
                        System.err.println("[testclient] no player yet for /" + command);
                        return;
                    }
                    Object connection = player.getClass().getField("connection").get(player);
                    connection.getClass().getMethod("sendCommand", String.class).invoke(connection, command);
                    System.out.println("[testclient] ran /" + command);
                } catch (Throwable t) {
                    System.err.println("[testclient] /" + command + " failed: " + t);
                }
            });
        } else if (line.startsWith("shoot ")) {
            int count = Integer.parseInt(line.substring("shoot ".length()).trim());
            Class<?> operatorClass = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator", true, gameLoader);
            Class<?> localPlayer = Class.forName("net.minecraft.client.player.LocalPlayer", true, gameLoader);
            Method fromLocalPlayer = operatorClass.getMethod("fromLocalPlayer", localPlayer);
            Method shoot = operatorClass.getMethod("shoot");
            for (int i = 1; i <= count; i++) {
                int shot = i;
                execute.invoke(minecraft, (Runnable) () -> {
                    try {
                        Object player = minecraftClass.getField("player").get(minecraft);
                        if (player == null) {
                            System.err.println("[testclient] no player to shoot with");
                            return;
                        }
                        Object result = shoot.invoke(fromLocalPlayer.invoke(null, player));
                        System.out.println("[testclient] shot " + shot + ": " + result);
                    } catch (Throwable t) {
                        System.err.println("[testclient] shot " + shot + " failed: " + t);
                    }
                });
                // An AK-47 cycles in 100 ms; leave each shot time to reach the server.
                Thread.sleep(350);
            }
            System.out.println("[testclient] shooting done");
        } else {
            System.err.println("[testclient] unknown remote command: " + line);
        }
    }
}
