package com.systemdesign.notification.service;

import com.systemdesign.notification.exception.TemplateNotFoundException;
import com.systemdesign.notification.model.NotificationTemplate;
import com.systemdesign.notification.repository.TemplateRepository;
import com.systemdesign.notification.template.SimpleTemplateEngine;

import java.util.Map;

/**
 * Loads, validates, and renders notification templates.
 */
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final SimpleTemplateEngine engine;

    public TemplateService(TemplateRepository templateRepository, SimpleTemplateEngine engine) {
        this.templateRepository = templateRepository;
        this.engine = engine;
    }

    /**
     * Load the template by ID, validate data completeness, and render subject + body.
     *
     * @return String[]{renderedSubject, renderedBody}
     * @throws TemplateNotFoundException if template does not exist
     */
    public String[] renderTemplate(String templateId, Map<String, String> data) {
        NotificationTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(
                        "Template not found: " + templateId));

        engine.validate(template, data);

        String subject = engine.render(template.getSubjectTemplate(), data);
        String body = engine.render(template.getBodyTemplate(), data);

        return new String[]{subject, body};
    }
}
