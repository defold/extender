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
        	return Mustache.compiler().compile(template).execute(context);
        } catch (MustacheException e) {
            LOGGER.error(String.format("Failed to substitute string '%s'", (String)template));
            throw e;
        }
    }

    public List<String> execute(List<String> templates, Map<String, Object> context) {
    	List<String> out = new ArrayList<>();
    	for (String template : templates) {
        	try {
    			out.add(Mustache.compiler().compile(template).execute(context));
	        } catch (MustacheException e) {
	            LOGGER.error(String.format("Failed to substitute string in list [..., '%s', ...]", (String)template));
	            throw e;
	        }
    	}
        return out;
    }
}
