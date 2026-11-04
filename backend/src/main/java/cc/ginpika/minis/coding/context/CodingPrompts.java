package cc.ginpika.minis.coding.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** Authored defaults kept separate from runtime metadata and user-owned overrides. */
public final class CodingPrompts {
    public static final String MAIN=cc.ginpika.minis.ai.core.prompt.skill.CodingSkill.DEFAULT;
    public static final String EXPLORE=load("explore");
    public static final String PLANNER=load("planner");
    public static final String REVIEWER=load("reviewer");

    private CodingPrompts(){}
    private static String load(String role){
        String path="coding/prompts/"+role+".md";
        try{return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();}
        catch(IOException error){throw new UncheckedIOException("编码角色提示词不可读："+path,error);}
    }
}
