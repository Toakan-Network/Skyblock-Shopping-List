package uk.toakan.tad.huddable.api;

/**
 * Compile-only copy of Huddable's plugin entrypoint (Huddable 0.4.4), so Shopping List builds without Huddable on a
 * Maven repo. Left out of the jar (build.gradle); at runtime Huddable's own class is used.
 */
@FunctionalInterface
public interface HuddablePlugin {
    void register();
}
