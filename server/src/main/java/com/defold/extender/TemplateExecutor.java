package com.defold.extender;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TemplateExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemplateExecutor.class);

    public String execute(String template, Map<String, Object> context) {
        try {
        	String result = Mustache.compiler().compile(template).execute(context);
            while (!result.equals(template)) {
                template = result;
                result = Mustache.compiler().compile(template).execute(context);
            }
            return result;
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to substitute string '%s'", (String)template));
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
                LOGGER.error(String.format("Failed to substitute string in list [..., '%s', ...]", (String)template));
                ExtenderUtil.debugPrint(context, 0);
                throw e;
	        }
    	}
        return out;
    }
}
