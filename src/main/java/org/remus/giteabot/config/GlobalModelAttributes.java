package org.remus.giteabot.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes attributes that every Thymeleaf page needs regardless of the
 * controller that rendered it: the application version, which the balloon-help
 * tour uses to scope its "already shown" cookie (a new release re-triggers the
 * tour), and the build id shown in the page footer.
 */
@ControllerAdvice
public class GlobalModelAttributes {

    private static final String FALLBACK_VERSION = "dev";
    private static final String FALLBACK_CONTAINER_ID = "local";

    private final String appVersion;
    private final String buildId;

    @Autowired
    public GlobalModelAttributes(ObjectProvider<BuildProperties> buildProperties) {
        this(buildProperties, System.getenv("HOSTNAME"));
    }

    GlobalModelAttributes(ObjectProvider<BuildProperties> buildProperties, String containerId) {
        BuildProperties build = buildProperties.stream().findFirst().orElse(null);
        this.appVersion = build != null && build.getVersion() != null ? build.getVersion() : FALLBACK_VERSION;
        String suffix = build != null ? build.get("suffix") : null;
        // Docker sets HOSTNAME to the short container id unless --hostname overrides it.
        String id = containerId == null || containerId.isBlank() ? FALLBACK_CONTAINER_ID : containerId.strip();
        this.buildId = suffix == null || suffix.isBlank() ? id : id + "-" + suffix;
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }

    /** Container id plus the build suffix, shown as a footer watermark to identify the running image. */
    @ModelAttribute("buildId")
    public String buildId() {
        return buildId;
    }

    @ModelAttribute("supportedLocales")
    public java.util.List<I18nConfig.LocaleOption> supportedLocales() {
        return I18nConfig.SUPPORTED;
    }
}
