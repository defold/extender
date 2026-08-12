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

    @Test
    public void minAndroidSdkVersionShouldRenderAsASingleArgumentToMinApi() {
        // The rendered command is split on spaces, so an unbound or multi-token
        // minAndroidSdkVersion would make d8 read the next flag as the value of --min-api.
        TemplateExecutor templateExecutor = new TemplateExecutor();
        String template = "d8 --min-api {{minAndroidSdkVersion}} --main-dex-rules {{mainDexList}}";
        Map<String, Object> context = new HashMap<>();
        context.put("minAndroidSdkVersion", 24);
        context.put("mainDexList", "/tmp/main.rules");
        String result = templateExecutor.execute(template, context);
        assertThat(result).isEqualTo("d8 --min-api 24 --main-dex-rules /tmp/main.rules");
        assertThat(result.split(" ")).hasSize(5);
    }

}
