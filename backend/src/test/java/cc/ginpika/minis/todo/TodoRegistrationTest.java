package cc.ginpika.minis.todo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;

/** Domain tests bypass Spring scanning; protect the application's shared bean namespace too. */
class TodoRegistrationTest {
    @Test void personalAndAgentTodosCoexistDuringApplicationComponentScanning() {
        try(var context=new GenericApplicationContext()) {
            var scanner=new ClassPathBeanDefinitionScanner(context);
            scanner.scan("cc.ginpika.minis.agent.chat","cc.ginpika.minis.agent.runtime","cc.ginpika.minis.todo");
            assertEquals(TodoService.class.getName(),context.getBeanDefinition("personalTodoService").getBeanClassName());
            assertEquals(TodoStore.class.getName(),context.getBeanDefinition("personalTodoStore").getBeanClassName());
            assertEquals(cc.ginpika.minis.agent.chat.TodoService.class.getName(),context.getBeanDefinition("todoService").getBeanClassName());
            assertNotEquals(TodoStore.class.getName(),context.getBeanDefinition("todoStore").getBeanClassName());
        }
    }
}
