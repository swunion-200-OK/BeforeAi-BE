package site.siyoung.beforeaibe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BeforeAiBeApplicationTests {

    @Test
    void contextLoads() {
    }

}
