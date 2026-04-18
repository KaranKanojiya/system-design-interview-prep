package com.systemdesign.notification.template;

import com.systemdesign.notification.exception.TemplateNotFoundException;
import com.systemdesign.notification.model.NotificationTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight template engine that replaces {{key}} placeholders with data values.
 * No external dependencies — suitable for interview demonstration of string processing.
 */
public class SimpleTemplateEngine {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * Replace all {{key}} placeholders in the template string with values from data.
     */
    public String render(String template, Map<String, String> data) {
        if (template == null) return null;

        String result = template;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /**
     * Extract all variable names referenced in a template string.
     */
    public List<String> extractVariables(String template) {
        List<String> variables = new ArrayList<>();
        if (template == null) return variables;

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!variables.contains(varName)) {
                variables.add(varName);
            }
        }
        return variables;
    }

    /**
     * Validate that all required variables in the template are present in the data map.
     */
    public void validate(NotificationTemplate template, Map<String, String> data) {
        if (template == null) {
            throw new TemplateNotFoundException("Template is null");
        }

        List<String> missing = new ArrayList<>();
        for (String required : template.getRequiredVariables()) {
            if (!data.containsKey(required)) {
                missing.add(required);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required template variables: " + missing +
                    " for template '" + template.getName() + "'");
        }
    }
}
