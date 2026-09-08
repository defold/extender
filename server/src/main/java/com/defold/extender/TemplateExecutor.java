package com.defold.extender;

import com.defold.extender.log.LogSanitizer;
import com.defold.extender.log.Markers;
import com.samskivert.mustache.Mustache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TemplateExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemplateExecutor.class);

    String executeOnceWithoutLogging(String template, Map<String, Object> context) {
        return Mustache.compiler().compile(template).execute(context);
    }

    String executeWithoutLogging(String template, Map<String, Object> context) {
        String result = executeOnceWithoutLogging(template, context);
        while (!result.equals(template)) {
            template = result;
            result = executeOnceWithoutLogging(template, context);
        }
        return result;
    }

    public String execute(String template, Map<String, Object> context) {
        try {
            return executeWithoutLogging(template, context);
        } catch (Exception e) {
            LOGGER.error(Markers.COMPILATION_ERROR, String.format("Failed to substitute string '%s'", LogSanitizer.sanitize(template)));
            ExtenderUtil.debugPrint(context, 0);
            throw e;
        }
    }

    public List<String> execute(List<String> templates, Map<String, Object> context) {
        List<String> out = new ArrayList<>();
        for (String template : templates) {
            try {
                out.add(this.execute(template, context));
            } catch (Exception e) {
                LOGGER.error(Markers.COMPILATION_ERROR, String.format("Failed to substitute string in list [..., '%s', ...]", LogSanitizer.sanitize(template)));
                ExtenderUtil.debugPrint(context, 0);
                throw e;
           }
        }
        return out;
    }
}
