package skyblockshoppinglist;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * How the shopping-list HUD looks: colours, sizes, text and which parts are drawn. Edited in Settings >
 * Appearance and saved to hud_style.json. Defaults match the original look.
 */
public final class HudStyle {
    public enum AmountFormat { REMAINING, HAVE_NEED, REQUIRED }
    public enum Align { LEFT, CENTRE, RIGHT }
    /** Where the Craftable / Forging sections go. */
    public enum Placement { MAIN_PANEL, OWN_PANEL }

    public static final String DEFAULT_FONT = "minecraft:default";

    // Colours (ARGB).
    public int panelBackground = 0x99271910;
    public int panelBorder = 0xFFDAA520;
    public int headerBackground = 0xCC2C4A1B;
    public int titleText = 0xFFFFB728;
    public int divider = 0x99608C35;
    public int rowBackground = 0x99271910;
    public int treeLines = 0xFF777777;
    public int itemText = 0xFFFFFFFF;
    public int rootText = 0xFFFFB728;
    public int done = 0xFF6EFF6E;
    public int partial = 0xFFFFA040;
    public int missing = 0xFFFF6B6B;
    public int craftable = 0xFFFFE45C;
    /** Held, but some of it is still cooking in the Forge. */
    public int cooking = 0xFF5CC8FF;
    public int sectionHeader = 0xFFFFFF55;
    public int sectionText = 0xFFFF9D00;
    public int forgingHeader = 0xFFFFFF55;
    public int forgingText = 0xFFFF9D00;
    /** Forging lines for items the shopping list doesn't need (Forging section set to All). */
    public int forgingOtherText = 0xFFFF6B6B;

    public int mobDropsHeader = 0xFFFFFF55;
    public int mobDropsText = 0xFFFF9D00;
    /** Mob drops lines past the 0 Magic Find average: the drop is overdue. */
    public int mobDropsOverdueText = 0xFFFF6B6B;

    /** Which forge slots the Forging section shows: none, those making something on the list, or all of them. */
    public enum ForgingSlots { OFF, LISTED, ALL }
    /** Which rows show the forge time still ahead, e.g. [25hrs]. */
    public enum ForgeTimes { OFF, TOP_LEVEL, ALL }
    /** Which rows get a progress bar behind them: none, the top row, the top row and its ingredients, or all. */
    public enum ProgressBars { OFF, TOP_LEVEL, FIRST_LEVEL, ALL }
    /**
     * Which kill estimates the Mob drops panel shows: lucky with your Magic Find, the average with it, the average
     * with none, or all three.
     */
    public enum KillCases { ALL, BEST, YOUR, WORST }
    /** How the HUD size reacts to the window: with its height, with GUI Scale, or not at all. */
    public enum Sizing { WINDOW, GUI_SCALE, FIXED }

    // Size: percent of the original size (as drawn in a 1080p window).
    public int hudScale = 100;
    public Sizing sizing = Sizing.WINDOW;
    /** Read only: the Follow GUI Scale toggle from before HUD sizing, carried over in {@link #clamp()}. */
    public Boolean followGuiScale;

    // Sizes (GUI pixels).
    public int rowHeight = 16;
    public int indent = 10;
    public int padding = 6;
    public int rowGap = 0;
    /** Space between a panel's title bar and its first row or line. */
    public int titleGap = 4;
    public int panelBorderWidth = 2;
    public int rowBorderWidth = 1;
    /** Corner rounding in pixels (0 = square). */
    public int panelRadius = 0;
    public int rowRadius = 0;

    // Text.
    public String font = DEFAULT_FONT;
    /** Row (recipe tree) text size. */
    public float textScale = 1.0f;
    public float titleScale = 1.0f;
    public float craftableScale = 1.0f;
    public float forgingScale = 1.0f;
    public float mobDropsScale = 1.0f;
    public Align titleAlign = Align.CENTRE;
    public Align craftableAlign = Align.CENTRE;
    public Align forgingAlign = Align.CENTRE;
    public Align mobDropsAlign = Align.CENTRE;
    public boolean textShadow = false;
    public boolean boldRootNames = true;

    // Row decoration.
    public boolean showTreeLines = true;
    public boolean showRowBoxes = true;
    public boolean showMarks = false;

    // Settings tab (what the HUD shows); kept when a preset is loaded, see keepSettings.
    public AmountFormat amountFormat = AmountFormat.REMAINING;
    /** Row amounts as 1.2k / 500m / 1.5b instead of the full number. */
    public boolean shortNumbers = true;
    public boolean showCraftable = true;
    public ForgingSlots forgingSlots = ForgingSlots.ALL;
    /** Read only: the Forging section on/off toggle from before Off / Listed / All, carried over in {@link #clamp()}. */
    public Boolean showForging;
    /** Coins to buy the list's raw materials at NPCs and what NPCs pay for them, at the bottom of the main panel. */
    public boolean showNpcPrice = true;
    /** A padlock after rows still to make whose recipe needs an unlock (collection, HotM, slayer level). */
    public boolean showRequirements = true;
    public ForgeTimes forgeTimes = ForgeTimes.ALL;
    /** Done ingredient rows (green) aren't drawn on the HUD. */
    public boolean hideFinished = true;
    /** Progress bars on the recipe trees, and separately on the Total. */
    public ProgressBars progressBars = ProgressBars.OFF;
    public ProgressBars totalProgressBars = ProgressBars.OFF;
    /** Kills-left estimates for list items that drop from mobs. */
    public boolean showMobDrops = false;
    public KillCases killCases = KillCases.ALL;
    /** Magic Find for the estimates; 0 reads it from the tab list or the last RARE DROP message. */
    public int magicFind = 0;
    // Panel placement is layout, so presets carry it.
    public Placement craftablePlacement = Placement.MAIN_PANEL;
    public Placement forgingPlacement = Placement.MAIN_PANEL;
    public Placement totalPlacement = Placement.MAIN_PANEL;
    public Placement mobDropsPlacement = Placement.OWN_PANEL;

    private static volatile HudStyle current;
    /** Style drawn instead of the active one on this thread (the Appearance preview), or null. */
    private static final ThreadLocal<HudStyle> OVERRIDE = new ThreadLocal<>();
    /** Font id -> description, cached so text drawing doesn't parse the id every frame. */
    private transient FontDescription fontDescription;
    /** The font id {@link #fontDescription} was made from (the preview changes {@link #font} on the fly). */
    private transient String fontDescriptionId;
    /** Text ready to draw, by text, colour and boldness; render thread only. Made for {@link #preparedFont}. */
    private transient Map<PreparedKey, Prepared> prepared;
    /** Names cut to fit a width ({@link #fitted}), by name, colour, boldness and room; reset with {@link #prepared}. */
    private transient Map<FitKey, Prepared> fitted;
    private transient String preparedFont;
    private transient int preparedGeneration;
    /** Bumped when glyph widths may have changed: a resource reload, or the Unicode / Japanese font options. */
    private static volatile int fontGeneration;
    /** The font options last seen by {@link #checkFontOptions()}; they change fonts without a resource reload. */
    private static boolean lastForceUnicode;
    private static boolean lastJapaneseGlyphs;

    /** Text laid out for drawing (visual order) and its width in font pixels. */
    public record Prepared(FormattedCharSequence seq, int width) {
        public static final Prepared EMPTY = new Prepared(FormattedCharSequence.EMPTY, 0);
    }

    private record PreparedKey(String text, int color, boolean bold) {}

    private record FitKey(String name, int color, boolean bold, int room) {}

    /** A map that drops its least recently used entry once it holds more than {@code max}. */
    private static final class Lru<K, V> extends LinkedHashMap<K, V> {
        private final int max;

        Lru(int max) {
            super(256, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
        }
    }

    /** The active style, loaded from disk on first use. */
    public static HudStyle get() {
        HudStyle override = OVERRIDE.get();
        if (override != null) return override;
        HudStyle style = current;
        if (style == null) {
            style = JsonFiles.read(FilePathManager.HUD_STYLE_JSON, HudStyle.class);
            if (style == null) style = new HudStyle();
            style.clamp();
            current = style;
        }
        return style;
    }

    /** Runs {@code action} with {@link #get()} returning {@code style} on this thread (for previews). */
    public static <T> T withOverride(HudStyle style, Supplier<T> action) {
        OVERRIDE.set(style);
        try {
            return action.get();
        } finally {
            OVERRIDE.remove();
        }
    }

    /** An independent copy of the active style. */
    public static HudStyle copy() {
        HudStyle copy = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(get()), HudStyle.class);
        copy.clamp();
        return copy;
    }

    /** True once hud_style.json exists (used to migrate the old Show remaining setting only once). */
    public static boolean isSaved() {
        return FilePathManager.HUD_STYLE_JSON.exists();
    }

    /** Writes the active style to disk. */
    public static void save() {
        HudStyle style = get();
        style.clamp();
        style.fontDescription = null;
        JsonFiles.write(FilePathManager.HUD_STYLE_JSON, style);
    }

    /**
     * Switches to {@code style} (from a preset) and saves. Presets are the look only: the player's HUD sizing and
     * Settings tab choices are kept.
     */
    public static void replace(HudStyle style) {
        style.keepSettings(get());
        style.fontDescription = null;
        style.clamp();
        current = style;
        save();
    }

    /** Puts every look setting back to its default and saves. Settings tab choices are kept. */
    public static void reset() {
        HudStyle style = new HudStyle();
        style.keepSettings(get());
        current = style;
        save();
    }

    /**
     * Copies what isn't part of the look from {@code from}: HUD sizing and the Settings tab choices (what the HUD
     * shows). These live here so the Appearance preview follows them, but presets and Reset leave them alone.
     */
    private void keepSettings(HudStyle from) {
        sizing = from.sizing;
        amountFormat = from.amountFormat;
        shortNumbers = from.shortNumbers;
        showCraftable = from.showCraftable;
        forgingSlots = from.forgingSlots;
        // An old preset's toggle would otherwise override the kept choice in clamp().
        showForging = null;
        showNpcPrice = from.showNpcPrice;
        showRequirements = from.showRequirements;
        forgeTimes = from.forgeTimes;
        hideFinished = from.hideFinished;
        progressBars = from.progressBars;
        totalProgressBars = from.totalProgressBars;
        showMobDrops = from.showMobDrops;
        killCases = from.killCases;
        magicFind = from.magicFind;
    }

    /** Forgets the in-memory copy (after a reset deleted the file). */
    public static void clear() {
        current = null;
    }

    private void clamp() {
        rowHeight = clamp(rowHeight, 10, 24);
        indent = clamp(indent, 4, 20);
        padding = clamp(padding, 0, 16);
        rowGap = clamp(rowGap, 0, 6);
        titleGap = clamp(titleGap, 0, 12);
        panelBorderWidth = clamp(panelBorderWidth, 0, 4);
        rowBorderWidth = clamp(rowBorderWidth, 0, 2);
        panelRadius = clamp(panelRadius, 0, 6);
        rowRadius = clamp(rowRadius, 0, 6);
        textScale = clamp(textScale, 0.5f, 2.0f);
        titleScale = clamp(titleScale, 0.5f, 2.0f);
        craftableScale = clamp(craftableScale, 0.5f, 2.0f);
        forgingScale = clamp(forgingScale, 0.5f, 2.0f);
        mobDropsScale = clamp(mobDropsScale, 0.5f, 2.0f);
        hudScale = clamp(hudScale, 50, 300);
        magicFind = clamp(magicFind, 0, 1000);
        if (titleAlign == null) titleAlign = Align.CENTRE;
        if (craftableAlign == null) craftableAlign = Align.CENTRE;
        if (forgingAlign == null) forgingAlign = Align.CENTRE;
        if (mobDropsAlign == null) mobDropsAlign = Align.CENTRE;
        if (craftablePlacement == null) craftablePlacement = Placement.MAIN_PANEL;
        if (forgingPlacement == null) forgingPlacement = Placement.MAIN_PANEL;
        if (totalPlacement == null) totalPlacement = Placement.MAIN_PANEL;
        if (mobDropsPlacement == null) mobDropsPlacement = Placement.OWN_PANEL;
        if (killCases == null) killCases = KillCases.ALL;
        if (font == null || Identifier.tryParse(font) == null) font = DEFAULT_FONT;
        if (amountFormat == null) amountFormat = AmountFormat.REMAINING;
        if (forgeTimes == null) forgeTimes = ForgeTimes.ALL;
        if (progressBars == null) progressBars = ProgressBars.OFF;
        if (totalProgressBars == null) totalProgressBars = ProgressBars.OFF;
        // Files from before HUD sizing: Follow GUI Scale ON keeps following it, OFF gets the new default.
        if (followGuiScale != null) {
            if (Boolean.TRUE.equals(followGuiScale)) sizing = Sizing.GUI_SCALE;
            followGuiScale = null;
        }
        if (sizing == null) sizing = Sizing.WINDOW;
        // Files from before Off / Listed / All: OFF stays off, ON gets the new default (every slot).
        if (showForging != null) {
            forgingSlots = showForging ? ForgingSlots.ALL : ForgingSlots.OFF;
            showForging = null;
        }
        if (forgingSlots == null) forgingSlots = ForgingSlots.ALL;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Text in the chosen font and colour. Unknown font ids fall back to Minecraft's default font. */
    public Component text(String text, int color, boolean bold) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color & 0xFFFFFF).withBold(bold).withFont(fontDescription()));
    }

    /**
     * {@link #text} laid out for drawing, kept so the HUD doesn't reorder and measure the same text every frame.
     * Render thread only.
     */
    public Prepared prepared(String text, int color, boolean bold) {
        checkCaches();
        PreparedKey key = new PreparedKey(text, color, bold);
        Prepared result = prepared.get(key);
        if (result == null) {
            result = measure(text, color, bold);
            prepared.put(key, result);
        }
        return result;
    }

    /**
     * {@code name} fitted into {@code room} font pixels as {@link SandboxWidget#fitName} does, kept like
     * {@link #prepared}. Only the result is kept: the texts tried while searching for the fit are not.
     */
    public Prepared fitted(String name, int color, boolean bold, int room) {
        checkCaches();
        FitKey key = new FitKey(name, color, bold, room);
        Prepared result = fitted.get(key);
        if (result == null) {
            result = SandboxWidget.fitName(name, room, text -> measure(text, color, bold), Prepared::width);
            fitted.put(key, result);
        }
        return result;
    }

    /** Starts the text caches over when the font or its glyph widths changed. */
    private void checkCaches() {
        int generation = fontGeneration;
        if (prepared == null || !font.equals(preparedFont) || generation != preparedGeneration) {
            // Least recently used first: amounts and forge times change, so old entries pile up and are dropped,
            // while the title, headers and rows drawn every frame stay.
            prepared = new Lru<>(1024);
            fitted = new Lru<>(256);
            preparedFont = font;
            preparedGeneration = generation;
        }
    }

    private Prepared measure(String text, int color, boolean bold) {
        FormattedCharSequence seq = text(text, color, bold).getVisualOrderText();
        return new Prepared(seq, Minecraft.getInstance().font.width(seq));
    }

    /** Glyph widths may have changed (resource reload); cached text measurements must be redone. */
    static void fontsChanged() {
        fontGeneration++;
    }

    /**
     * Notices the Force Unicode and Japanese glyph options, which rebuild the fonts without a resource reload.
     * Called once per panel drawn rather than per text. Render thread only.
     */
    static void checkFontOptions() {
        var options = Minecraft.getInstance().options;
        boolean unicode = options.forceUnicodeFont().get();
        boolean japanese = options.japaneseGlyphVariants().get();
        if (unicode != lastForceUnicode || japanese != lastJapaneseGlyphs) {
            lastForceUnicode = unicode;
            lastJapaneseGlyphs = japanese;
            fontsChanged();
        }
    }

    /** Changes whenever cached text widths go stale. */
    static int fontGeneration() {
        return fontGeneration;
    }

    private FontDescription fontDescription() {
        FontDescription description = fontDescription;
        if (description == null || !font.equals(fontDescriptionId)) {
            Identifier id = Identifier.tryParse(font);
            description = id == null ? FontDescription.DEFAULT : new FontDescription.Resource(id);
            fontDescription = description;
            fontDescriptionId = font;
        }
        return description;
    }

    /** Every font the loaded resource packs provide (assets/<ns>/font/<name>.json), default first. */
    public static List<String> availableFonts() {
        List<String> fonts = new ArrayList<>();
        FileToIdConverter converter = FileToIdConverter.json("font");
        for (Identifier file : converter.listMatchingResources(Minecraft.getInstance().getResourceManager()).keySet()) {
            Identifier id = converter.fileToId(file);
            // "include/..." fonts are building blocks other fonts pull in, not fonts to pick.
            if (!id.getPath().startsWith("include/")) fonts.add(id.toString());
        }
        fonts.sort(null);
        fonts.remove(DEFAULT_FONT);
        fonts.add(0, DEFAULT_FONT);
        return fonts;
    }
}
