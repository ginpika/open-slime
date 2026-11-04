package cc.ginpika.minis.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * 分层守门：钉死「agent → ai.knowledge / ai.memory → ai.core」的单向依赖。
 *
 * <p>背景：曾经 {@code ai.core.AiContext} 反向 import {@code agent.chat.store} 与
 * {@code ai.knowledge.store}（下层依赖上层），与上层的正向依赖一起绕成包级构造环，
 * 只能靠 {@code @Lazy} 撑住。拆解后 ai.core 回归纯底层（prompt / tool / util），
 * 本测试防止反向依赖重新长出来——不修依赖方向的改动会让构建当场失败。
 *
 * <p>允许的例外：core/knowledge 都可以依赖 {@code config}（配置属性）与 {@code stats}
 * （token 计量）——它们是被所有层共享的基础设施，不属于本规则要保护的业务分层。
 *
 * <p>ai.memory 与 ai.knowledge 是平级域包：agent 只许 push 进来（MemoryWriter 门面），
 * memory 不回头读编排层；memory → knowledge 只允许出现在固化管道（M2，届时再单独放行该管道包）。
 */
class LayeringTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cc.ginpika.minis");

    private static final String CORE = "cc.ginpika.minis.ai.core..";
    private static final String KNOWLEDGE = "cc.ginpika.minis.ai.knowledge..";
    private static final String MEMORY = "cc.ginpika.minis.ai.memory..";
    private static final String TERMINAL = "cc.ginpika.minis.ai.computer.terminal..";
    private static final String AGENT = "cc.ginpika.minis.agent..";
    @Test
    void computerCapabilitiesDoNotDependOnOrchestrationOrTransport() {
        assertTrue(CLASSES.containPackage("cc.ginpika.minis.ai.computer"));
        noClasses().that().resideInAPackage("cc.ginpika.minis.ai.computer..")
                .should().dependOnClassesThat().resideInAnyPackage(AGENT, "cc.ginpika.minis.web..", KNOWLEDGE, MEMORY)
                .check(CLASSES);
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAPackage("cc.ginpika.minis.ai.computer..")
                .check(CLASSES);
    }
    @Test
    void comfyUiCapabilityIsIndependentOfChatAndTransport() {
        assertTrue(CLASSES.containPackage("cc.ginpika.minis.ai.comfyui"));
        noClasses().that().resideInAPackage("cc.ginpika.minis.ai.comfyui..")
                .should().dependOnClassesThat().resideInAnyPackage(AGENT, "cc.ginpika.minis.web..", KNOWLEDGE, MEMORY)
                .check(CLASSES);
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAPackage("cc.ginpika.minis.ai.comfyui..")
                .check(CLASSES);
    }
    @Test
    void codingExecutionIsIndependentOfChatAndTransport() {
        noClasses().that().resideInAnyPackage("cc.ginpika.minis.coding.engine..", "cc.ginpika.minis.coding.context..", "cc.ginpika.minis.coding.repository..", "cc.ginpika.minis.coding.runner..", "cc.ginpika.minis.coding.store..")
                .should().dependOnClassesThat().resideInAnyPackage(AGENT,"cc.ginpika.minis.web..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("cc.ginpika.minis.ai.runtime..")
                .should().dependOnClassesThat().resideInAnyPackage(AGENT,MEMORY,"cc.ginpika.minis.coding..","cc.ginpika.minis.web..")
                .check(CLASSES);
    }

    @Test
    void agentServicesDoNotDependOnWebTransport() {
        noClasses().that().resideInAPackage(AGENT)
                .should().dependOnClassesThat().resideInAPackage("cc.ginpika.minis.web..")
                .because("应用编排不依赖 HTTP 控制器，流式回调契约归应用层")
                .check(CLASSES);
    }

    @Test
    void contextAssemblyDoesNotExecuteAgentsOrTools() {
        noClasses().that().haveSimpleName("ChatContextService")
                .should().dependOnClassesThat().haveSimpleName("AgentRuntime")
                .because("上下文预览与真实请求共用只读组装路径，不运行模型循环")
                .check(CLASSES);
        noClasses().that().haveSimpleName("ChatContextService")
                .should().dependOnClassesThat().haveSimpleName("ChatToolDispatcher")
                .because("上下文组装不执行工具")
                .check(CLASSES);
    }

    @Test
    void coreDoesNotDependOnKnowledge() {
        /* 防空转：确认类确实被扫进来了（否则规则恒真，守门形同虚设） */
        assertTrue(CLASSES.containPackage("cc.ginpika.minis.ai.core"));
        ArchRule rule = noClasses()
                .that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAPackage(KNOWLEDGE)
                .because("ai.core 是最底层（提示词 / 工具协议），不得反向依赖知识库检索与存储");
        rule.check(CLASSES);
    }

    @Test
    void coreDoesNotDependOnAgent() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAPackage(AGENT)
                .because("ai.core 不得依赖编排层（chat / 潜意识 / 子代理），否则又成构造环");
        rule.check(CLASSES);
    }

    @Test
    void knowledgeDoesNotDependOnAgent() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(KNOWLEDGE)
                .should().dependOnClassesThat().resideInAPackage(AGENT)
                .because("知识库侧只提供检索与存储能力，不得反向依赖编排层"
                        + "（跨层的「当前 Agent」作用域放在 ai.core.AgentScope）");
        rule.check(CLASSES);
    }

    @Test
    void memoryDoesNotDependOnAgent() {
        /* 防空转：M0 起就有类落在 ai.memory 包 */
        assertTrue(CLASSES.containPackage("cc.ginpika.minis.ai.memory"));
        ArchRule rule = noClasses()
                .that().resideInAPackage(MEMORY)
                .should().dependOnClassesThat().resideInAPackage(AGENT)
                .because("ai.memory 与 ai.knowledge 平级：agent 的「说过/做过」只能 push 进来"
                        + "（MemoryWriter 门面），记忆侧不得回头读编排层");
        rule.check(CLASSES);
    }

    @Test
    void memoryDoesNotDependOnKnowledge() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(MEMORY)
                .should().dependOnClassesThat().resideInAPackage(KNOWLEDGE)
                .because("memory → knowledge 只允许出现在固化管道（M2）——届时再为该管道包放行，"
                        + "M1 的向量轴/融合读侧都不许碰知识库的内部");
        rule.check(CLASSES);
    }

    @Test
    void terminalDoesNotDependOnAgent() {
        /* 防空转：命令行终端自己就是一个独立域包 */
        assertTrue(CLASSES.containPackage("cc.ginpika.minis.ai.computer.terminal"));
        ArchRule rule = noClasses()
                .that().resideInAPackage(TERMINAL)
                .should().dependOnClassesThat().resideInAPackage(AGENT)
                .because("ai.terminal 是被编排层调用的能力（工具定义在 ai.core.tool.terminal，"
                        + "执行与配置在 ai.terminal），与 ai.knowledge / ai.memory 平级，"
                        + "不得反向依赖会话与潜意识");
        rule.check(CLASSES);
    }

    @Test
    void terminalDoesNotDependOnKnowledgeOrMemory() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(TERMINAL)
                .should().dependOnClassesThat().resideInAnyPackage(KNOWLEDGE, MEMORY)
                .because("执行一条命令与知识库检索、记忆召回无关；三者各自独立演进，"
                        + "需要串起来时由编排层组合，不在这里互相 import");
        rule.check(CLASSES);
    }
}
