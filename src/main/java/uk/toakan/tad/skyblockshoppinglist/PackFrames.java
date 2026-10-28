package uk.toakan.tad.skyblockshoppinglist;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vanilla frame art (tooltip, popup, button) from a chosen resource pack. Vanilla's GUI atlas only holds the top
 * pack's copy of a sprite, so a lower pack's copy is read from that pack and drawn here with the same stretch / tile
 * / nine-slice rules. Only chosen art is loaded, on first draw; a resource reload drops it. Shared copy: Shopping
 * List is the reference, Huddable has a copy (package, mod id and logger aside).
 */
public final class PackFrames {
    private PackFrames() {}

    private static final FileToIdConverter SPRITES = new FileToIdConverter("textures/gui/sprites", ".png");

    /** A loaded sprite: its texture, the texture's size, the first frame's size (animated sprites) and scaling. */
    private record Art(Identifier texture, int textureWidth, int textureHeight, int frameWidth, int frameHeight,
                       GuiSpriteScaling scaling) {}

    private record Key(Identifier sprite, String pack) {}

    /**
     * Per sprite and pack, the art, or empty when that pack doesn't have it (or it failed to load). Keyed by pack too,
     * so the Appearance preview and the HUD can use different packs without reloading each frame.
     */
    private static final Map<Key, Optional<Art>> LOADED = new HashMap<>();

    /** The vanilla frames there are: their atlas sprites (bottom first) and where content goes inside them. */
    public enum Kind {
        TOOLTIP("tooltip", Identifier.withDefaultNamespace("tooltip/background"),
            Identifier.withDefaultNamespace("tooltip/frame"), 4, 9),
        POPUP("popup", Identifier.withDefaultNamespace("popup/background"), null, 6, 0),
        BUTTON("button", Identifier.withDefaultNamespace("widget/button"), null, 3, 0);

        /** The name saved in settings. */
        public final String id;
        public final Identifier sprite;
        /** Drawn over {@link #sprite} (the tooltip's frame), or null. */
        public final Identifier overlay;
        /** Space from the box's edge that keeps content off the edge art. */
        public final int inset;
        /** Empty border inside the sprites: tooltips are drawn 9 px out, as vanilla's TooltipRenderUtil does. */
        public final int margin;

        Kind(String id, Identifier sprite, Identifier overlay, int inset, int margin) {
            this.id = id;
            this.sprite = sprite;
            this.overlay = overlay;
            this.inset = inset;
            this.margin = margin;
        }

        /** The kind saved as {@code id}, or null. */
        public static Kind byId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) return kind;
            }
            return null;
        }

        private List<Identifier> sprites() {
            return overlay == null ? List.of(sprite) : List.of(sprite, overlay);
        }
    }

    /** Enabled packs that have art for any of {@code kinds}, top of the pack list first. */
    public static List<String> packsWith(Kind... kinds) {
        Minecraft client = Minecraft.getInstance();
        ResourceManager manager = client.getResourceManager();
        Set<String> found = new LinkedHashSet<>();
        for (Kind kind : kinds) {
            for (Identifier sprite : kind.sprites()) {
                for (Resource resource : manager.getResourceStack(SPRITES.idToFile(sprite))) {
                    found.add(resource.sourcePackId());
                }
            }
        }
        List<String> packs = new ArrayList<>();
        for (Pack pack : client.getResourcePackRepository().getSelectedPacks()) {
            if (found.contains(pack.getId())) packs.add(0, pack.getId());
        }
        return packs;
    }

    /** A pack's name as the pack list shows it, or its id when it is no longer there. */
    public static String title(String packId) {
        Pack pack = Minecraft.getInstance().getResourcePackRepository().getPack(packId);
        return pack == null ? packId : pack.getTitle().getString();
    }

    /** True when {@code packId} is an enabled pack. */
    public static boolean enabled(String packId) {
        for (Pack pack : Minecraft.getInstance().getResourcePackRepository().getSelectedPacks()) {
            if (pack.getId().equals(packId)) return true;
        }
        return false;
    }

    /**
     * Draws a {@code kind} frame over the box, each sprite from {@code packId} ("" = the top pack) or, when that pack
     * doesn't have it, the top pack's.
     */
    public static void drawFrame(GuiGraphicsExtractor context, Kind kind, String packId, int x, int y, int w, int h) {
        int m = kind.margin;
        for (Identifier sprite : kind.sprites()) {
            if (packId.isEmpty() || !draw(context, sprite, packId, x - m, y - m, w + 2 * m, h + 2 * m)) {
                context.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x - m, y - m, w + 2 * m, h + 2 * m);
            }
        }
    }

    /** Draws {@code sprite} from {@code packId} over the box; false when that pack has no such sprite. */
    private static boolean draw(GuiGraphicsExtractor context, Identifier sprite, String packId, int x, int y, int w, int h) {
        Key key = new Key(sprite, packId);
        Optional<Art> art = LOADED.get(key);
        if (art == null) {
            art = load(sprite, packId, LOADED.size());
            LOADED.put(key, art);
        }
        if (art.isEmpty()) return false;
        Art a = art.get();
        switch (a.scaling()) {
            case GuiSpriteScaling.Tile tile -> tile(context, a, x, y, w, h, 0, 0, tile.width(), tile.height(),
                tile.width(), tile.height());
            case GuiSpriteScaling.NineSlice nineSlice -> nineSlice(context, a, nineSlice, x, y, w, h);
            default -> region(context, a, x, y, w, h, 0, 0, 1, 1, 1, 1);
        }
        return true;
    }

    /** Drops the loaded art (resource packs reloaded). */
    public static void reloaded() {
        for (Optional<Art> art : LOADED.values()) {
            art.ifPresent(a -> Minecraft.getInstance().getTextureManager().release(a.texture()));
        }
        LOADED.clear();
    }

    private static Optional<Art> load(Identifier sprite, String packId, int number) {
        for (Resource resource : Minecraft.getInstance().getResourceManager().getResourceStack(SPRITES.idToFile(sprite))) {
            if (!resource.sourcePackId().equals(packId)) continue;
            try (InputStream in = resource.open()) {
                NativeImage image = NativeImage.read(in);
                ResourceMetadata metadata = resource.metadata();
                GuiSpriteScaling scaling = metadata.getSection(GuiMetadataSection.TYPE)
                    .map(GuiMetadataSection::scaling).orElse(GuiSpriteScaling.DEFAULT);
                // Animated sprites are a strip of frames; the first one is used.
                FrameSize frame = metadata.getSection(AnimationMetadataSection.TYPE)
                    .map(anim -> anim.calculateFrameSize(image.getWidth(), image.getHeight()))
                    .orElse(new FrameSize(image.getWidth(), image.getHeight()));
                Identifier id = Identifier.fromNamespaceAndPath(ShoppingList.MOD_ID, "pack_frame/" + number);
                int width = image.getWidth();
                int height = image.getHeight();
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
                return Optional.of(new Art(id, width, height, frame.width(), frame.height(), scaling));
            } catch (IOException | RuntimeException e) {
                ShoppingList.LOGGER.warn("Couldn't load {} from {}: {}", sprite, packId, e.toString());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Draws part of the sprite, given in sprite units ({@code u, v, uw, vh} out of {@code unitsW x unitsH}), over the
     * box. Units are scaled to the texture's pixels, so a 64x pack's art stays sharp.
     */
    private static void region(GuiGraphicsExtractor context, Art a, int x, int y, int w, int h,
                               float u, float v, float uw, float vh, int unitsW, int unitsH) {
        if (w <= 0 || h <= 0 || uw <= 0 || vh <= 0) return;
        float sx = (float) a.frameWidth() / unitsW;
        float sy = (float) a.frameHeight() / unitsH;
        context.blit(RenderPipelines.GUI_TEXTURED, a.texture(), x, y, u * sx, v * sy, w, h,
            Math.max(1, Math.round(uw * sx)), Math.max(1, Math.round(vh * sy)), a.textureWidth(), a.textureHeight());
    }

    /** Repeats a sprite part across the box at 1 unit = 1 GUI pixel, cutting the last copy short. */
    private static void tile(GuiGraphicsExtractor context, Art a, int x, int y, int w, int h,
                             int u, int v, int uw, int vh, int unitsW, int unitsH) {
        if (w <= 0 || h <= 0 || uw <= 0 || vh <= 0) return;
        for (int dy = 0; dy < h; dy += vh) {
            int ph = Math.min(vh, h - dy);
            for (int dx = 0; dx < w; dx += uw) {
                int pw = Math.min(uw, w - dx);
                region(context, a, x + dx, y + dy, pw, ph, u, v, pw, ph, unitsW, unitsH);
            }
        }
    }

    /** Fixed corners, tiled (or stretched) edges and centre, as vanilla draws nine-slice GUI sprites. */
    private static void nineSlice(GuiGraphicsExtractor context, Art a, GuiSpriteScaling.NineSlice slice,
                                  int x, int y, int w, int h) {
        GuiSpriteScaling.NineSlice.Border border = slice.border();
        int nw = slice.width();
        int nh = slice.height();
        int l = Math.min(border.left(), w / 2);
        int r = Math.min(border.right(), w / 2);
        int t = Math.min(border.top(), h / 2);
        int b = Math.min(border.bottom(), h / 2);
        int innerW = w - l - r;
        int innerH = h - t - b;
        int srcW = nw - l - r;
        int srcH = nh - t - b;
        region(context, a, x, y, l, t, 0, 0, l, t, nw, nh);
        region(context, a, x + w - r, y, r, t, nw - r, 0, r, t, nw, nh);
        region(context, a, x, y + h - b, l, b, 0, nh - b, l, b, nw, nh);
        region(context, a, x + w - r, y + h - b, r, b, nw - r, nh - b, r, b, nw, nh);
        inner(context, a, slice, x + l, y, innerW, t, l, 0, srcW, t);
        inner(context, a, slice, x + l, y + h - b, innerW, b, l, nh - b, srcW, b);
        inner(context, a, slice, x, y + t, l, innerH, 0, t, l, srcH);
        inner(context, a, slice, x + w - r, y + t, r, innerH, nw - r, t, r, srcH);
        inner(context, a, slice, x + l, y + t, innerW, innerH, l, t, srcW, srcH);
    }

    private static void inner(GuiGraphicsExtractor context, Art a, GuiSpriteScaling.NineSlice slice,
                              int x, int y, int w, int h, int u, int v, int uw, int vh) {
        if (slice.stretchInner()) {
            region(context, a, x, y, w, h, u, v, uw, vh, slice.width(), slice.height());
        } else {
            tile(context, a, x, y, w, h, u, v, uw, vh, slice.width(), slice.height());
        }
    }
}
