package cc.ginpika.minis.ai.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RollDiceTool implements AiTool {

    public static final String NAME = "roll_dice";

    private static final int MAX_SIDES = Integer.MAX_VALUE;
    private static final int MAX_COUNT = 10_000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "为跑团检定、伤害判定或其他随机判定投掷骰子。每次独立等概率地得到 1 到 sides 的整数，"
                + "返回 count 次投掷的点数和 total。需要实际骰点时调用，例如 2d6 对应 sides=6、count=2。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode().put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("sides").put("type", "integer").put("description", "骰子的面数")
                .put("minimum", 1).put("maximum", MAX_SIDES);
        props.putObject("count").put("type", "integer").put("description", "投掷次数")
                .put("minimum", 1).put("maximum", MAX_COUNT);
        params.putArray("required").add("sides").add("count");
        return params;
    }

    public static String roll(JsonNode args) {
        JsonNode sides = args.path("sides");
        if (!isInRange(sides, MAX_SIDES)) {
            return "sides（面数）必须是 1 到 " + MAX_SIDES + " 之间的整数";
        }
        JsonNode count = args.path("count");
        if (!isInRange(count, MAX_COUNT)) {
            return "count（投掷次数）必须是 1 到 " + MAX_COUNT + " 之间的整数";
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int faceCount = sides.intValue();
        int rollCount = count.intValue();
        long total = 0;
        for (int i = 0; i < rollCount; i++) {
            total += random.nextInt(faceCount) + 1;
        }
        return JsonNodeFactory.instance.objectNode().put("total", total).toString();
    }

    private static boolean isInRange(JsonNode value, int max) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 1 && value.intValue() <= max;
    }
}
