package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 知识提取（文档分析步骤 2）：从文本抽取三元组，经 submit_triples 回传 */
@Component
public class AnalyzeBlock implements PromptBlock {

    public static final String KEY = "analyze";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "知识提取";
    }

    @Override
    public String description() {
        return "从文本抽取 (Subject, Predicate, Object) 三元组，经 submit_triples 回传。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_ANALYZE);
    }

    @Override
    public String builtin() {
        return """
                你是知识图谱的知识抽取与语义提炼专家。请从文本中抽取具备高度概括性和核心价值的 (Subject, Predicate, Object) 三元组。
                【分类讨论】
                1. 你应该尝试结合自己预训练时的参数化知识,去评估当前文本描述的知识
                2. 对于动漫、轻小说、漫画、游戏、电影等虚构场景, 用户关心的是虚构世界观下的知识完整度
                3. 对于真实场景的知识, 用户关心的是提取和关联的准确度, 比如苹果和苹果公司在知识图谱中是不同的实体

                【抽取原则】
                1. 提炼实质，重点关注实体间的核心关联、社会关系、重大事件、因果或深层情感。
                2. 允许合理抽象：通过上下文语境和内部知识,将复杂的行为或对话提炼为高层级的关系
                3. 规范化节点：
                - Subject / Object: 使用明确的具体实体名（人/组织/概念/事件），不要使用代词（如“她”、“这个项目”）。
                - Predicate: 使用精炼的中文动词或关系词（如：暗恋、合作、对抗、导致、创立），避免冗长的句子。
                - Type: 为两端实体各自判断类型，大写英文单词（如 CHARACTER、PERSON、ORG、PLACE、ITEM、EVENT、WORK、CONCEPT），
                  按语义自由命名不限于示例，分别填入 subject_type / object_type。
                - Aliases: 实体的别名、简称、全称、外文名/日文名、通称等他称，分别填入 subject_aliases / object_aliases 数组，
                  用于检索时用别名也能找到该实体；没有则留空，不要写入 props。

                【特殊要求】
                1. 对于ACGN角色,应该尝试提取萌属性比如
                - （初音未来——萌属性——双马尾）
                - （冬马和纱——萌属性——黑长直）
                - （长门有希——萌属性——三无）
                - （夏娜——萌属性——傲娇）
                2. 对于ACGN角色,如果世界观属于现代,应该提取她们的生日,格式为X月X日,比如
                - (雪之下雪乃——生日——1月3日)
                3. 对于虚构概念如:动漫或游戏中的人物/场所/组织/物品/魔法/招数等,应直接和登场作品本身关联,关系为世界观

                【过滤】
                1. 当存在某实体单独列出无归纳意义时,比如(雪之下雪乃——学号——38号),其中"38号"作为实体单独列出无意义,
                但"雪之下雪乃"作为人物有明确实体意义时,应对实体"雪之下雪乃"添加props:学号:38号.其他类似概念有血型、年龄、性别、座右铭、名台词、擅长科目
                2. 别名类信息(别名、简称、全称、外文名、通称)不写 props,填入 subject_aliases / object_aliases

                请分析用户文本,列出有价值的三元组,并调用 submit_triples 工具提交结果。""";
    }
}
