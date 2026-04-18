package com.systemdesign.notification.config;

import com.systemdesign.notification.handler.*;
import com.systemdesign.notification.model.*;
import com.systemdesign.notification.queue.InMemoryPriorityQueue;
import com.systemdesign.notification.queue.NotificationQueue;
import com.systemdesign.notification.repository.*;
import com.systemdesign.notification.service.*;
import com.systemdesign.notification.template.SimpleTemplateEngine;
import com.systemdesign.notification.controller.NotificationController;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Manual dependency injection / wiring — the role Spring's ApplicationContext
 * would play in a framework-based application.
 */
public class AppConfig {

    public static final int MAX_RETRIES_PUSH = 5;
    public static final int MAX_RETRIES_EMAIL = 5;
    public static final int MAX_RETRIES_SMS = 3;

    private final NotificationRepository notificationRepository;
    private final PreferenceRepository preferenceRepository;
    private final TemplateRepository templateRepository;

    public AppConfig() {
        this.notificationRepository = new InMemoryNotificationRepository();
        this.preferenceRepository = new InMemoryPreferenceRepository();
        this.templateRepository = new InMemoryTemplateRepository();
    }

    public NotificationService createNotificationService() {
        // Handler map — Strategy pattern: one handler per channel
        Map<Channel, NotificationHandler> handlers = new EnumMap<>(Channel.class);
        handlers.put(Channel.PUSH, new PushNotificationHandler());
        handlers.put(Channel.EMAIL, new EmailNotificationHandler());
        handlers.put(Channel.SMS, new SmsNotificationHandler());
        handlers.put(Channel.IN_APP, new InAppNotificationHandler());

        // Services
        PreferenceService preferenceService = new PreferenceService(preferenceRepository);
        SimpleTemplateEngine engine = new SimpleTemplateEngine();
        TemplateService templateService = new TemplateService(templateRepository, engine);
        DeliveryTracker deliveryTracker = new DeliveryTracker();
        NotificationQueue queue = new InMemoryPriorityQueue();

        return new NotificationService(
                notificationRepository,
                preferenceService,
                templateService,
                deliveryTracker,
                queue,
                handlers
        );
    }

    public NotificationController createController() {
        seedTemplates(templateRepository);
        seedPreferences(preferenceRepository);
        return new NotificationController(createNotificationService());
    }

    /**
     * Seed sample templates for the demo.
     */
    public static void seedTemplates(TemplateRepository repo) {
        repo.save(new NotificationTemplate(
                "order-confirmation", "order-confirmation", Channel.EMAIL,
                "Order {{orderId}} Confirmed",
                "Hi {{name}}, your order {{orderId}} for {{item}} has been confirmed. Total: {{amount}}",
                List.of("name", "orderId", "item", "amount")
        ));

        repo.save(new NotificationTemplate(
                "otp-verification", "otp-verification", Channel.SMS,
                null,
                "Your OTP is {{otp}}. Valid for {{minutes}} minutes. Do not share.",
                List.of("otp", "minutes")
        ));

        repo.save(new NotificationTemplate(
                "price-drop-alert", "price-drop-alert", Channel.PUSH,
                "Price Drop on {{item}}",
                "{{item}} is now {{newPrice}} (was {{oldPrice}}). {{discount}}% off!",
                List.of("item", "newPrice", "oldPrice", "discount")
        ));

        repo.save(new NotificationTemplate(
                "welcome-message", "welcome-message", Channel.IN_APP,
                "Welcome to the platform!",
                "Hi {{name}}, welcome aboard! Start exploring features.",
                List.of("name")
        ));
    }

    /**
     * Seed user preferences for the demo:
     * - alice: all channels enabled, normal quiet hours
     * - bob: SMS disabled
     * - carol: currently in quiet hours (for demo purposes)
     */
    public static void seedPreferences(PreferenceRepository repo) {
        // Alice: all enabled, standard quiet hours (22-8)
        UserPreference alice = new UserPreference("alice");
        repo.save(alice);

        // Bob: SMS disabled
        UserPreference bob = new UserPreference("bob");
        bob.setChannelEnabled(Channel.SMS, false);
        repo.save(bob);

        // Carol: quiet hours set to encompass current time (to demonstrate blocking)
        UserPreference carol = new UserPreference("carol");
        carol.setQuietHoursStart(0);
        carol.setQuietHoursEnd(23); // effectively always quiet
        repo.save(carol);
    }

    // --- Accessors for advanced usage ---

    public NotificationRepository getNotificationRepository() { return notificationRepository; }
    public PreferenceRepository getPreferenceRepository() { return preferenceRepository; }
    public TemplateRepository getTemplateRepository() { return templateRepository; }
}
