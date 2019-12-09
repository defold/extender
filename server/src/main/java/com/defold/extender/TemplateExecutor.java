package com.defold.extender;

import com.samskivert.mustache.Mustache;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TemplateExecutor {

    public String execute(String template, Map<String, Object> context) {
        return Mustache.compiler().compile(template).execute(context);
    }

    public List<String> execute(List<String> templates, Map<String, Object> context) {
        return templates.stream()
                .map(template -> Mustache.compiler().compile(template).execute(context))
                .collect(Collectors.toList());
    }
}
