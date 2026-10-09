package testclient;

import java.lang.instrument.Instrumentation;

/**
 * Java agent for unattended test clients (Windows): the game window never takes keyboard focus and sits minimized
 * in the taskbar, so a test launch never pulls focus away from whatever the user is working in.
 * <p>
 * It patches LWJGL's {@code org.lwjgl.glfw.GLFW} class (see {@link GlfwTransformer}), which every loader loads
 * unchanged, so the same agent works on Fabric, Quilt, Forge and NeoForge, in development and in production.
 * Minecraft 26.2 still opens a GLFW window: it creates it hidden ({@code Window.createGlfwWindow}), sets its
 * callbacks in the {@code Window} constructor, the last being {@code glfwSetWindowIconifyCallback}, and shows it
 * later from {@code Minecraft}'s constructor ({@code glfwShowWindow}).
 * <p>
 * {@link RemoteControl} then lets a test script drive the minimized client without keyboard or mouse input.
 * Never part of the mod: only a test launch puts it on the command line ({@code tools/release-test.ps1}).
 */
public final class MinimizedWindowAgent {
    private MinimizedWindowAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new GlfwTransformer());
        RemoteControl.start(instrumentation);
    }
}
