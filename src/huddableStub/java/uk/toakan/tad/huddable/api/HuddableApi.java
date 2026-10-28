package uk.toakan.tad.huddable.api;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Compile-only copy of the parts of Huddable's API (Huddable 0.5.0) Shopping List calls; signatures only. Left out
 * of the jar (build.gradle); at runtime Huddable's own class is used. Keep in step with Huddable's
 * {@code uk.toakan.tad.huddable.api.HuddableApi}.
 */
public final class HuddableApi {
    private HuddableApi() {}

    public record Box(int x, int y, int width, int height) {}

    @FunctionalInterface
    public interface BoxProvider {
        Box box(int screenWidth, int screenHeight);
    }

    public record Spec(BoxProvider defaultBox, DoubleSupplier baseScale, boolean resizable, int minWidth, int minHeight,
                       BooleanSupplier listed) {
        public static Spec of(BoxProvider defaultBox) {
            throw new UnsupportedOperationException("stub");
        }

        public Spec withBaseScale(DoubleSupplier scale) {
            throw new UnsupportedOperationException("stub");
        }

        public Spec resizable(int minWidth, int minHeight) {
            throw new UnsupportedOperationException("stub");
        }

        public Spec listedWhen(BooleanSupplier listed) {
            throw new UnsupportedOperationException("stub");
        }
    }

    public record Placement(int x, int y, int width, int height, float scale, boolean hidden) {}

    public static void register(Identifier id, Component label, Spec spec) {
        throw new UnsupportedOperationException("stub");
    }

    public static void ignoreLayer(Identifier fabricLayerId) {
        throw new UnsupportedOperationException("stub");
    }

    public static Placement placement(Identifier id) {
        throw new UnsupportedOperationException("stub");
    }

    public static void openEditor(Screen parent) {
        throw new UnsupportedOperationException("stub");
    }

    public static int frameInset(Identifier id) {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean drawFrame(GuiGraphicsExtractor graphics, Identifier id, int x, int y, int width, int height) {
        throw new UnsupportedOperationException("stub");
    }
}
