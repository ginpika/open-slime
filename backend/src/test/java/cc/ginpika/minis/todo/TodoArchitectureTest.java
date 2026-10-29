package cc.ginpika.minis.todo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class TodoArchitectureTest {
    @Test void personalWorkDoesNotDependOnAgentExecutionMemoryOrWebIdentity() {
        var classes=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("cc.ginpika.minis.todo");
        noClasses().that().resideInAPackage("cc.ginpika.minis.todo..")
                .should().dependOnClassesThat().resideInAnyPackage("cc.ginpika.minis.agent..","cc.ginpika.minis.ai..","cc.ginpika.minis.web..")
                .check(classes);
    }
}
