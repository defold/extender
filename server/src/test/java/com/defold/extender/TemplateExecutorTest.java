package com.defold.extender;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TemplateExecutorTest {

    @Test
    public void templateVariablesShouldBeReplacedByContext() {
        TemplateExecutor templateExecutor = new TemplateExecutor();
        String template = "Hello {{name}}!";
        Map<String, Object> context = new HashMap<>();
        context.put("name", "James");
        String result = templateExecutor.execute(template, context);
        assertThat(result).isEqualTo("Hello James!");
    }

}
