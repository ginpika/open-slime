package cc.ginpika.minis.ai.core;

/**
 * 记忆的**身份上下文**（memory-v6 §3.4 / §11）：这条信息是「谁说的、在什么身份下说的」。
 *
 * <p>用于 role-play 隔离：同一用户可以有「现实身份」与「虚构身份」。系统必须区分
 * ——角色讨厌某物不代表用户讨厌它；剧情里的选择不代表用户的价值观（设计大纲 §十）。
 *
 * <p>硬规则（memory-v6 §11）：
 * <ul>
 *   <li>{@link #FICTION} 的来源永不进入用户画像，最高只到项目域；</li>
 *   <li>{@link #PROJECT_PREF} 只落项目域，不自动升画像；</li>
 *   <li>{@link #INFERRED} 只能形成画像候选，升画像需 repeated + 审批。</li>
 * </ul>
 */
public enum MemoryIdentity {
    /** 用户在现实中的陈述（普通会话上下文） */
    REAL,
    /** 用户对虚构角色的设定 / 为推进剧情作出的选择（创作类项目） */
    FICTION,
    /** 用户在某个项目中的创作偏好 */
    PROJECT_PREF,
    /** 系统由多次行为推断出的潜在信息 */
    INFERRED;

    /** 由「项目是否创作类」派生缺省身份：创作类 → FICTION，否则 → REAL */
    public static MemoryIdentity ofNarrative(boolean narrative) {
        return narrative ? FICTION : REAL;
    }

    /** 解析字符串（迁移 / 外部输入用）：未知或空 → {@link #INFERRED}（保守） */
    public static MemoryIdentity parse(String value) {
        if (value == null || value.isBlank()) {
            return INFERRED;
        }
        for (MemoryIdentity id : values()) {
            if (id.name().equalsIgnoreCase(value.trim())) {
                return id;
            }
        }
        return INFERRED;
    }

    /** 由来源类型（source_kind：fiction / project_pref / inferred / 其它）映射身份；空 → {@link #REAL} */
    public static MemoryIdentity ofSourceKind(String sourceKind) {
        if (sourceKind == null || sourceKind.isBlank()) {
            return REAL;
        }
        return switch (sourceKind.trim().toLowerCase()) {
            case "fiction" -> FICTION;
            case "project_pref" -> PROJECT_PREF;
            case "inferred" -> INFERRED;
            default -> REAL;
        };
    }
}
