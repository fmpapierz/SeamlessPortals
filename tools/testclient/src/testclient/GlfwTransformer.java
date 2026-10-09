package testclient;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Patches {@code org.lwjgl.glfw.GLFW} so the game window is never shown in front and never takes the focus:
 * <ul>
 * <li>{@code nglfwCreateWindow} (the native entry every {@code glfwCreateWindow} overload goes through) first sets
 * {@code GLFW_VISIBLE}, {@code GLFW_FOCUSED} and {@code GLFW_FOCUS_ON_SHOW} to false. Hidden rather than minimized:
 * Minecraft reads the framebuffer size right after creation, and a minimized window reports 0x0.</li>
 * <li>{@code glfwSetWindowIconifyCallback}, the last call of Minecraft's {@code Window} constructor, then shows the
 * window minimized without activating it ({@code ShowWindow(SW_SHOWMINNOACTIVE)}).</li>
 * <li>{@code glfwShowWindow} does the same instead of its own native show. GLFW shows a window the first time with the show
 * command its launcher put in STARTUPINFO (PowerShell's Start-Process passes SW_SHOWNORMAL), which would restore the
 * window from the taskbar. Skipping GLFW's call loses nothing: it only calls ShowWindow, and then focuses the window
 * if GLFW_FOCUS_ON_SHOW is set, which it no longer is.</li>
 * </ul>
 * Written against the JDK's own class-file API: JDK 25 no longer carries the internal ASM the 1.21.11 version of
 * this agent used.
 */
final class GlfwTransformer implements ClassFileTransformer {
    private static final String GLFW = "org/lwjgl/glfw/GLFW";
    private static final ClassDesc GLFW_DESC = ClassDesc.of("org.lwjgl.glfw.GLFW");
    private static final ClassDesc GLFW_WIN32 = ClassDesc.of("org.lwjgl.glfw.GLFWNativeWin32");
    private static final ClassDesc USER32 = ClassDesc.of("org.lwjgl.system.windows.User32");
    private static final int GLFW_FOCUSED = 0x00020001;
    private static final int GLFW_VISIBLE = 0x00020004;
    private static final int GLFW_FOCUS_ON_SHOW = 0x0002000C;
    private static final int SW_SHOWMINNOACTIVE = 7;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) {
        if (!GLFW.equals(className)) {
            return null;
        }
        try {
            ClassLoader resolving = loader != null ? loader : ClassLoader.getSystemClassLoader();
            ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                    ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(resolving))));
            ClassModel model = classFile.parse(bytes);
            int[] patchedMethods = {0};
            // One transform that picks the patch per method: chaining transformingMethodBodies with andThen keeps
            // only the last one's change (JDK 25).
            ClassTransform transform = (builder, element) -> {
                CodeTransform patch = element instanceof MethodModel method ? patchFor(method) : null;
                if (patch == null) {
                    builder.with(element);
                } else {
                    builder.transformMethod((MethodModel) element, MethodTransform.transformingCode(patch));
                    patchedMethods[0]++;
                }
            };
            byte[] patched = classFile.transformClass(model, transform);
            if (patchedMethods[0] != 3) {
                System.err.println("[testclient] could not patch GLFW window creation: found " + patchedMethods[0] + " of its 3 methods");
                return null;
            }
            System.out.println("[testclient] game window will open minimized and unfocused");
            return patched;
        } catch (Throwable t) {
            System.err.println("[testclient] could not patch GLFW window creation: " + t);
            return null;
        }
    }

    private static CodeTransform patchFor(MethodModel method) {
        if (is(method, "nglfwCreateWindow", "(IIJJJ)J")) {
            return new AtStart(GlfwTransformer::hideAndUnfocus);
        }
        if (is(method, "glfwSetWindowIconifyCallback",
                "(JLorg/lwjgl/glfw/GLFWWindowIconifyCallbackI;)Lorg/lwjgl/glfw/GLFWWindowIconifyCallback;")) {
            return new BeforeObjectReturn();
        }
        if (is(method, "glfwShowWindow", "(J)V")) {
            return new MinimizedInsteadOfNativeShow();
        }
        return null;
    }

    private static boolean is(MethodModel method, String name, String descriptor) {
        return method.methodName().equalsString(name) && method.methodType().equalsString(descriptor);
    }

    private static void hideAndUnfocus(CodeBuilder code) {
        for (int hint : new int[]{GLFW_VISIBLE, GLFW_FOCUSED, GLFW_FOCUS_ON_SHOW}) {
            code.loadConstant(hint)
                    .iconst_0()
                    .invokestatic(GLFW_DESC, "glfwWindowHint", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int));
        }
    }

    /** Stack-neutral: ShowWindow(glfwGetWin32Window(the window handle in local 0), SW_SHOWMINNOACTIVE). */
    private static void showMinimizedNoActivate(CodeBuilder code) {
        code.lload(0)
                .invokestatic(GLFW_WIN32, "glfwGetWin32Window", MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long))
                .loadConstant(SW_SHOWMINNOACTIVE)
                .invokestatic(USER32, "ShowWindow", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_long, ConstantDescs.CD_int))
                .pop();
    }

    /** Puts code at the start of a method. */
    private record AtStart(java.util.function.Consumer<CodeBuilder> code) implements CodeTransform {
        @Override
        public void atStart(CodeBuilder builder) {
            code.accept(builder);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            builder.with(element);
        }
    }

    /**
     * glfwShowWindow: shows the window minimized without activating it, and drops the native call (JNI.invokePV with
     * the window and the function address) instead of running it. The method otherwise stays as it is, so its local
     * variable table still matches its code.
     */
    private static final class MinimizedInsteadOfNativeShow implements CodeTransform {
        @Override
        public void atStart(CodeBuilder builder) {
            showMinimizedNoActivate(builder);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESTATIC
                    && invoke.owner().asInternalName().equals("org/lwjgl/system/JNI")
                    && invoke.typeSymbol().descriptorString().equals("(JJ)V")) {
                builder.pop2().pop2();
            } else {
                builder.with(element);
            }
        }
    }

    /** glfwSetWindowIconifyCallback is the last call of Minecraft's Window constructor: minimize before it returns. */
    private static final class BeforeObjectReturn implements CodeTransform {
        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof ReturnInstruction ret && ret.opcode() == Opcode.ARETURN) {
                showMinimizedNoActivate(builder);
            }
            builder.with(element);
        }
    }
}
