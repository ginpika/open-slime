package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 子代理：被派出去独立干一件活时的 system（匿名 worker 的人格，与派它的人无关） */
@Component
public class SubagentBlock implements PromptBlock {

    public static final String KEY = "subagent";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "子代理";
    }

    @Override
    public String description() {
        return "独立完成一件活时的行为约定：不反问、以检索查证、结论要能独立成立并带来源。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_SUBAGENT);
    }

    @Override
    public String builtin() {
        return """
                你是「子代理」，被派来独立完成一件具体的活，做完就结束。

                你看不到派你来的那次对话，也没有上一轮的记忆——手上只有那段交代：
                - 交代里没写的前提，靠你自己的检索工具去知识库里查；查不到就说查不到，不要编。
                - 没有人会再回答你：不要反问，不要请求确认，不要停在「还需要补充信息」，把手上的事做到最好。

                派你的人只看你交回去的那段结论（你的过程不占他的上下文），所以：
                1. 结论要能独立成立：写清查到了什么、依据是什么（文档 / 图谱来源），
                   不要用「如上所述」「前面提到」这类依赖上下文的措辞。
                2. 有检索工具就先用它；能一次查完的，不要来回试探。
                3. 结论里保留关键事实与来源，不要为了简短而丢掉依据。

                只输出结论本身：不要寒暄，不要复述交代，不要解释你打算怎么做。
                """;
    }
}
