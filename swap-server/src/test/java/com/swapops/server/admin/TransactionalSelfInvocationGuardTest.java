package com.swapops.server.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务自调用守卫（批次32）：<b>字节码级</b>扫描 {@code @Transactional} 方法的自调用。
 *
 * <p>守的是什么：Spring 的 {@code @Transactional} 靠代理生效，类内 {@code this.method()} 调用
 * 不经过代理，注解<b>静默失效</b>——不报错、不告警，只是没有事务。这类缺陷在单测（多为 Mock，
 * 看不到代理）、SpotBugs（看不见运行时装配）、覆盖率（那行代码确实被"执行"了）三层防线里全都隐形；
 * 批次32 的退款双记事故（加款已提交、CAS 未做、重驱动二次入账）根因正是它。
 *
 * <p>判定规则（刻意收窄，避免误报）：
 * <ol>
 *   <li>调用点与目标同类（含内部类调用外部类）且目标方法是 {@code @Transactional}；</li>
 *   <li><b>且调用方自己不是 {@code @Transactional}</b>——若调用方本身有事务，被调方即使走 {@code this}
 *       也仍在该事务内（REQUIRED 语义等价）。{@code ChannelReconService#importBill → reconcile}
 *       就是这种良性自调用，不该被报。</li>
 * </ol>
 *
 * <p>为什么用 ASM 读字节码而不是 grep 源码：源码层面的正则匹配无法可靠区分"定义/调用/同名局部变量/
 * 字符串字面量"，字节码里的 {@code INVOKEVIRTUAL/INVOKESPECIAL} 指令是确定的。
 * ASM 由 spring-core 自带（{@code org.springframework.asm}），不新增依赖。
 */
@DisplayName("事务自调用守卫（字节码扫描）")
class TransactionalSelfInvocationGuardTest {

    @Test
    @DisplayName("全仓扫描：无 '@Transactional 方法被非事务方法自调用' 的静默失效点")
    void noTransactionalSelfInvocation() throws Exception {
        Path root = mainClassesRoot();
        List<Class<?>> classes = loadAllClasses(root);

        List<String> violations = new ArrayList<>();
        int transactionalMethods = 0;
        int scannedClasses = 0;

        for (Class<?> clazz : classes) {
            Map<String, Method> transactional = transactionalMethods(clazz);
            if (transactional.isEmpty()) {
                continue;
            }
            scannedClasses++;
            transactionalMethods += transactional.size();

            Path classFile = root.resolve(clazz.getName().replace('.', '/') + ".class");
            if (!Files.exists(classFile)) {
                continue;
            }
            byte[] bytecode = Files.readAllBytes(classFile);
            Set<String> internalOwners = internalOwners(clazz);
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    String callerKey = name + descriptor;
                    boolean callerTransactional = transactional.containsKey(callerKey);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String targetName,
                                                    String targetDescriptor, boolean isInterface) {
                            if (callerTransactional || !internalOwners.contains(owner)) {
                                return;
                            }
                            Method target = transactional.get(targetName + targetDescriptor);
                            if (target != null) {
                                violations.add(clazz.getName() + "#" + name + " 自调用 @Transactional 方法 "
                                        + target.getName() + "（注解不会生效：资金/状态动作会失去原子性）");
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        assertThat(scannedClasses)
                .as("扫描到的含事务方法的类数量（防线自检：目录定位失败会让本测试变成空转）")
                .isGreaterThanOrEqualTo(10);
        assertThat(transactionalMethods)
                .as("扫描到的事务方法数量（同上，防止静默空转）")
                .isGreaterThanOrEqualTo(20);
        assertThat(violations).as("事务自调用问题").isEmpty();
    }

    @Test
    @DisplayName("守卫自检：探针类里刻意制造的违规必须被抓到（证伪防线本身没有空转）")
    void guardDetectsKnownViolation() throws Exception {
        Map<String, Method> transactional = transactionalMethods(SelfInvocationProbe.class);
        assertThat(transactional).as("探针的 @Transactional 方法可被反射识别").isNotEmpty();

        // 探针是测试类（编译进 target/test-classes），所以按它自己的 code source 定位，
        // 而不是主类目录——否则这里会误报"探针没编译"。
        Path probeRoot = Paths.get(SelfInvocationProbe.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path classFile = probeRoot.resolve(SelfInvocationProbe.class.getName().replace('.', '/') + ".class");
        assertThat(Files.exists(classFile)).as("探针类已编译（%s）", classFile).isTrue();

        List<String> found = new ArrayList<>();
        Set<String> owners = internalOwners(SelfInvocationProbe.class);
        new ClassReader(Files.readAllBytes(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                boolean callerTransactional = transactional.containsKey(name + descriptor);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String targetName,
                                                String targetDescriptor, boolean isInterface) {
                        if (!callerTransactional && owners.contains(owner)
                                && transactional.containsKey(targetName + targetDescriptor)) {
                            found.add(name + " -> " + targetName);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertThat(found)
                .as("只报 outer->inner；benign（事务方法调事务方法）不算违规——否则规则过宽会误报良性自调用")
                .containsExactly("outer -> inner");
    }

    // ---------- 内部 ----------

    /** 目标方法集合：键为 {@code name + JVM 描述符}（同名重载靠描述符区分）。 */
    private static Map<String, Method> transactionalMethods(Class<?> clazz) {
        Map<String, Method> result = new LinkedHashMap<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Transactional.class)) {
                result.put(method.getName() + Type.getMethodDescriptor(method), method);
            }
        }
        return result;
    }

    /**
     * "自己"的范围：本类 + 外层类（内部类通过 {@code this$0} 调外层事务方法同样不经过代理）
     * + 直接嵌套的内部类（外层调内部类实例方法也不是代理调用）。
     */
    private static Set<String> internalOwners(Class<?> clazz) {
        java.util.Set<String> owners = new java.util.LinkedHashSet<>();
        owners.add(clazz.getName().replace('.', '/'));
        Class<?> enclosing = clazz.getEnclosingClass();
        while (enclosing != null) {
            owners.add(enclosing.getName().replace('.', '/'));
            enclosing = enclosing.getEnclosingClass();
        }
        for (Class<?> nested : clazz.getDeclaredClasses()) {
            owners.add(nested.getName().replace('.', '/'));
        }
        return owners;
    }

    private static Path mainClassesRoot() throws Exception {
        URL mainRoot = com.swapops.server.SwapServerApplication.class.getProtectionDomain()
                .getCodeSource().getLocation();
        assertThat(mainRoot).as("主类目录可见（测试依赖 main classes）").isNotNull();
        return Paths.get(mainRoot.toURI());
    }

    private static List<Class<?>> loadAllClasses(Path root) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.startsWith("com/swapops/server/")) {
                    continue;
                }
                String className = relative.substring(0, relative.length() - ".class".length())
                        .replace('/', '.');
                try {
                    classes.add(Class.forName(className, false, loader));
                } catch (Throwable ignored) {
                    // 无法加载的类（缺可选依赖等）不参与扫描
                }
            }
        }
        return classes;
    }

    /**
     * 守卫自身的探针：{@code outer} 不是事务方法却自调用 {@code @Transactional inner}
     * ——这正是禁止的形状；{@code benign}（事务方法调事务方法）与 {@code external}（外部调用）
     * 都不该被判违规，用来确认规则没有过宽。
     */
    static class SelfInvocationProbe {

        private final Map<String, String> calls = new HashMap<>();

        void outer() {
            inner();
        }

        @Transactional
        void inner() {
            calls.put("k", "v");
        }

        @Transactional
        void benign() {
            inner();
        }
    }
}
