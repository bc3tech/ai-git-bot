package org.remus.giteabot.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.StringJoiner;

/**
 * Exposes attributes that every Thymeleaf page needs regardless of the
 * controller that rendered it: the application version, which the balloon-help
 * tour uses to scope its "already shown" cookie (a new release re-triggers the
 * tour), and the build identity shown in the page footer.
 */
@ControllerAdvice
public class GlobalModelAttributes {

    private static final String FALLBACK_VERSION = "dev";
    private static final String UNKNOWN = "unknown";

    private final String appVersion;
    private final String buildId;
    private final String buildDetails;

    public GlobalModelAttributes(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.stream().findFirst().orElse(null);
        this.appVersion = build != null && build.getVersion() != null ? build.getVersion() : FALLBACK_VERSION;

        // The fingerprint is a hash of the source baked into the image, so it identifies the image
        // contents rather than the container it happens to run in.
        String fingerprint = valueOr(build, "fingerprint", FALLBACK_VERSION);
        String suffix = valueOr(build, "suffix", null);
        this.buildId = suffix == null ? fingerprint : fingerprint + "-" + suffix;

        StringJoiner details = new StringJoiner(" | ");
        details.add("version " + appVersion);
        String commit = valueOr(build, "commit", UNKNOWN);
        if (!UNKNOWN.equals(commit)) {
            details.add("commit " + commit);
        }
        if (build != null && build.getTime() != null) {
            details.add("built " + build.getTime());
        }
        this.buildDetails = details.toString();
    }

    private static String valueOr(BuildProperties build, String key, String fallback) {
        String value = build != null ? build.get(key) : null;
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }

    /** Source fingerprint plus the build suffix, shown as a footer watermark to identify the running image. */
    @ModelAttribute("buildId")
    public String buildId() {
        return buildId;
    }

    /** Version, commit and build time, shown as the footer watermark's tooltip. */
    @ModelAttribute("buildDetails")
    public String buildDetails() {
        return buildDetails;
    }

    @ModelAttribute("supportedLocales")
    public java.util.List<I18nConfig.LocaleOption> supportedLocales() {
        return I18nConfig.SUPPORTED;
    }
}
