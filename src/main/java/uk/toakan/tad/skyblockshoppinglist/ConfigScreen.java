package uk.toakan.tad.skyblockshoppinglist;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.gui.image.ImageRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The Settings screen, built with YetAnotherConfigLib. Values are stored by {@link SandboxWidget} on Save. Text
 * comes from the lang file: the option helpers take a key and read the name from it and the description from
 * {@code key.desc}.
 */
public final class ConfigScreen {
    private ConfigScreen() {}

    public static Screen create(Screen parent) {
        SandboxWidget widget = SandboxWidget.getInstance();
        HudStyle style = HudStyle.get();
        HudStyle d = new HudStyle();
        List<String> fonts = HudStyle.availableFonts();
        if (!fonts.contains(style.font)) fonts.add(style.font);
        // The preview draws a copy that follows every unsaved change; the real look only changes on Save.
        HudStyle draft = HudStyle.copy();
        previewDraft = draft;
        previewImage = new ImageRenderer() {
            @Override
            public int render(GuiGraphicsExtractor context, int x, int y, int width, float delta) {
                return SandboxWidget.getInstance().renderPreview(context, x, y, width, draft);
            }

            @Override
            public void close() {
            }
        };
        try {
            return YetAnotherConfigLib.createBuilder()
                .title(Component.literal(ShoppingList.NAME))
                .category(settings(widget, d))
                .category(appearance(parent, d, fonts))
                .category(advanced(widget))
                // Widget setters save the widget config themselves; HudStyle values are written here.
                .save(HudStyle::save)
                .build()
                .generateScreen(parent);
        } finally {
            previewDraft = null;
            previewImage = null;
        }
    }

    /** Settings > Settings: what the mod shows and does. Look-only options are under Appearance. */
    private static ConfigCategory settings(SandboxWidget widget, HudStyle d) {
        return ConfigCategory.createBuilder()
            .name(Component.translatable("skyblock-shopping-list.config.settings"))
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.hud"))
                .option(toggle("skyblock-shopping-list.config.hud.enabled", false, widget::isEnabled, widget::setEnabled))
                .option(Option.<Integer>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.hud.max_recipes"))
                    .description(OptionDescription.of(Component.translatable("skyblock-shopping-list.config.hud.max_recipes.desc")))
                    .binding(3, widget::getMaxRecipes, widget::setMaxRecipes)
                    .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(1, SandboxWidget.MAX_RECIPES_LIMIT).step(1))
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.hud.move"))
                    .text(Component.translatable("skyblock-shopping-list.config.open"))
                    .description(OptionDescription.of(Component.translatable("skyblock-shopping-list.config.hud.move.desc")))
                    .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.hud.reset_position"))
                    .text(Component.translatable("skyblock-shopping-list.config.reset"))
                    .description(OptionDescription.of(Component.translatable("skyblock-shopping-list.config.hud.reset_position.desc")))
                    .action((screen, button) -> widget.resetPanelPositions())
                    .build())
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.sections"))
                .option(toggle("skyblock-shopping-list.config.sections.total", true, widget::isShowTotal, widget::setShowTotal))
                .option(placement("skyblock-shopping-list.config.sections.total_panel", d.totalPlacement,
                    s -> s.totalPlacement, (s, v) -> s.totalPlacement = v))
                .option(Option.<Integer>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.sections.total_level"))
                    .description(OptionDescription.of(Component.translatable("skyblock-shopping-list.config.sections.total_level.desc")))
                    .binding(0, widget::getTotalLevel, widget::setTotalLevel)
                    .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(0, SandboxWidget.MAX_TOTAL_LEVEL).step(1)
                        .formatValue(v -> v == 0
                            ? Component.translatable("skyblock-shopping-list.config.sections.total_level.raw")
                            : Component.translatable("skyblock-shopping-list.config.sections.total_level.level", v)))
                    .build())
                .option(toggle("skyblock-shopping-list.config.sections.craftable", d.showCraftable,
                    s -> s.showCraftable, (s, v) -> s.showCraftable = v))
                .option(placement("skyblock-shopping-list.config.sections.craftable_panel", d.craftablePlacement,
                    s -> s.craftablePlacement, (s, v) -> s.craftablePlacement = v))
                .option(styled(Option.<HudStyle.ForgingSlots>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.sections.forging"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.sections.forging.desc")))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.ForgingSlots.class)
                        .formatValue(v -> Component.translatable(switch (v) {
                            case OFF -> "skyblock-shopping-list.config.sections.forging.off";
                            case LISTED -> "skyblock-shopping-list.config.sections.forging.listed";
                            case ALL -> "skyblock-shopping-list.config.sections.forging.all";
                        }))),
                    d.forgingSlots, s -> s.forgingSlots, (s, v) -> s.forgingSlots = v))
                .option(placement("skyblock-shopping-list.config.sections.forging_panel", d.forgingPlacement,
                    s -> s.forgingPlacement, (s, v) -> s.forgingPlacement = v))
                .option(toggle("skyblock-shopping-list.config.sections.mob_drops", d.showMobDrops,
                    s -> s.showMobDrops, (s, v) -> s.showMobDrops = v))
                .option(placement("skyblock-shopping-list.config.sections.mob_drops_panel", d.mobDropsPlacement,
                    s -> s.mobDropsPlacement, (s, v) -> s.mobDropsPlacement = v))
                .option(styled(Option.<HudStyle.KillCases>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.sections.kill_cases"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.sections.kill_cases.desc")))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.KillCases.class)
                        .formatValue(v -> Component.translatable(switch (v) {
                            case ALL -> "skyblock-shopping-list.config.sections.kill_cases.all";
                            case BEST -> "skyblock-shopping-list.config.sections.kill_cases.best";
                            case YOUR -> "skyblock-shopping-list.config.sections.kill_cases.you";
                            case WORST -> "skyblock-shopping-list.config.sections.kill_cases.worst";
                        }))),
                    d.killCases, s -> s.killCases, (s, v) -> s.killCases = v))
                .option(slider("skyblock-shopping-list.config.sections.magic_find", 0, 1000, d.magicFind,
                    s -> s.magicFind, (s, v) -> s.magicFind = v))
                .option(ButtonOption.createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.sections.reset_kills"))
                    .text(Component.translatable("skyblock-shopping-list.config.reset"))
                    .description(OptionDescription.of(Component.translatable("skyblock-shopping-list.config.sections.reset_kills.desc")))
                    .action((screen, button) -> KillTracker.resetAll())
                    .build())
                .option(toggle("skyblock-shopping-list.config.sections.npc_price", d.showNpcPrice,
                    s -> s.showNpcPrice, (s, v) -> s.showNpcPrice = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.rows"))
                .option(styled(Option.<HudStyle.AmountFormat>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.rows.amount_format"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.rows.amount_format.desc")))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.AmountFormat.class)
                        .formatValue(v -> Component.translatable(switch (v) {
                            case REMAINING -> "skyblock-shopping-list.config.rows.amount_format.remaining";
                            case HAVE_NEED -> "skyblock-shopping-list.config.rows.amount_format.have_need";
                            case REQUIRED -> "skyblock-shopping-list.config.rows.amount_format.required";
                        }))),
                    d.amountFormat, s -> s.amountFormat, (s, v) -> s.amountFormat = v))
                .option(toggle("skyblock-shopping-list.config.rows.short_numbers", d.shortNumbers,
                    s -> s.shortNumbers, (s, v) -> s.shortNumbers = v))
                .option(toggle("skyblock-shopping-list.config.rows.hide_finished", d.hideFinished,
                    s -> s.hideFinished, (s, v) -> s.hideFinished = v))
                .option(progressBars("skyblock-shopping-list.config.rows.progress_bars", d.progressBars,
                    s -> s.progressBars, (s, v) -> s.progressBars = v))
                .option(progressBars("skyblock-shopping-list.config.rows.total_progress_bars", d.totalProgressBars,
                    s -> s.totalProgressBars, (s, v) -> s.totalProgressBars = v))
                .option(styled(Option.<HudStyle.ForgeTimes>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.rows.forge_times"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.rows.forge_times.desc")))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.ForgeTimes.class)
                        .formatValue(v -> Component.translatable(switch (v) {
                            case OFF -> "skyblock-shopping-list.config.rows.forge_times.off";
                            case TOP_LEVEL -> "skyblock-shopping-list.config.rows.forge_times.top_level";
                            case ALL -> "skyblock-shopping-list.config.rows.forge_times.all";
                        }))),
                    d.forgeTimes, s -> s.forgeTimes, (s, v) -> s.forgeTimes = v))
                .option(toggle("skyblock-shopping-list.config.rows.recipe_locks", d.showRequirements,
                    s -> s.showRequirements, (s, v) -> s.showRequirements = v))
                .option(styled(Option.<HudStyle.EventDrops>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.rows.event_drops"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.rows.event_drops.desc")))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.EventDrops.class)
                        .formatValue(v -> Component.translatable(switch (v) {
                            case OFF -> "skyblock-shopping-list.config.rows.event_drops.off";
                            case PERK_ACTIVE -> "skyblock-shopping-list.config.rows.event_drops.perk_active";
                            case EVENT_RUNNING -> "skyblock-shopping-list.config.rows.event_drops.event_running";
                        }))),
                    d.eventDrops, s -> s.eventDrops, (s, v) -> s.eventDrops = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.list"))
                .option(toggle("skyblock-shopping-list.config.list.auto_remove", true, widget::isAutoRemove, widget::setAutoRemove))
                .option(toggle("skyblock-shopping-list.config.list.deepest_first", false, widget::isStockDeepestFirst,
                    widget::setStockDeepestFirst))
                .option(toggle("skyblock-shopping-list.config.list.item_list_button", true, widget::isItemListButton,
                    widget::setItemListButton))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.notifications"))
                .option(channel(widget, Notifier.Type.READY, "skyblock-shopping-list.config.notifications.ready"))
                .option(channel(widget, Notifier.Type.ACHIEVED, "skyblock-shopping-list.config.notifications.achieved"))
                .option(channel(widget, Notifier.Type.SACK_PROMPT, "skyblock-shopping-list.config.notifications.sack_prompt"))
                .option(channel(widget, Notifier.Type.STALE_SACKS, "skyblock-shopping-list.config.notifications.stale_sacks"))
                .build())
            .build();
    }

    private static ConfigCategory advanced(SandboxWidget widget) {
        return ConfigCategory.createBuilder()
            .name(Component.translatable("skyblock-shopping-list.config.advanced"))
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.advanced.key_binds"))
                .text(Component.translatable("skyblock-shopping-list.config.open"))
                .description(OptionDescription.of(Component.translatable("skyblock-shopping-list.config.advanced.key_binds.desc",
                    ShoppingList.NAME)))
                .action((screen, button) -> Minecraft.getInstance().gui.setScreen(
                    new KeyBindsScreen(screen, Minecraft.getInstance().options)))
                .build())
            .option(toggle("skyblock-shopping-list.config.advanced.debug", false, widget::isDebugLogging, widget::setDebugLogging))
            .build();
    }

    /** Settings > Appearance: how the HUD looks, grouped. What it shows is under Settings. */
    private static ConfigCategory appearance(Screen parent, HudStyle d, List<String> fonts) {
        return ConfigCategory.createBuilder()
            .name(Component.translatable("skyblock-shopping-list.config.appearance"))
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.appearance.preview"))
                .text(Component.translatable("skyblock-shopping-list.config.open"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.appearance.preview.desc")))
                .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.appearance.reset"))
                .text(Component.translatable("skyblock-shopping-list.config.reset"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.appearance.reset.desc",
                    HudPresets.BACKUP_NAME)))
                .action((screen, button) -> {
                    HudPresets.backup();
                    HudStyle.reset();
                    Minecraft.getInstance().gui.setScreen(create(parent));
                })
                .build())
            .group(presets(parent))
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.size"))
                .option(slider("skyblock-shopping-list.config.size.hud_scale", 50, 300,
                    d.hudScale, s -> s.hudScale, (s, v) -> s.hudScale = v))
                .option(styled(Option.<HudStyle.Sizing>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.size.sizing"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.size.sizing.desc")))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Sizing.class)
                        .formatValue(v -> Component.translatable(switch (v) {
                            case WINDOW -> "skyblock-shopping-list.config.size.sizing.window";
                            case GUI_SCALE -> "skyblock-shopping-list.config.size.sizing.gui_scale";
                            case FIXED -> "skyblock-shopping-list.config.size.sizing.fixed";
                        }))),
                    d.sizing, s -> s.sizing, (s, v) -> s.sizing = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.text"))
                .option(styled(Option.<String>createBuilder()
                    .name(Component.translatable("skyblock-shopping-list.config.text.font"))
                    .description(describe(Component.translatable("skyblock-shopping-list.config.text.font.desc")))
                    .controller(opt -> DropdownStringControllerBuilder.create(opt).values(fonts)),
                    d.font, s -> s.font, (s, v) -> s.font = v))
                .option(size("skyblock-shopping-list.config.text.title_size", d.titleScale,
                    s -> s.titleScale, (s, v) -> s.titleScale = v))
                .option(align("skyblock-shopping-list.config.text.title_align", d.titleAlign, s -> s.titleAlign,
                    (s, v) -> s.titleAlign = v))
                .option(size("skyblock-shopping-list.config.text.row_size", d.textScale,
                    s -> s.textScale, (s, v) -> s.textScale = v))
                .option(size("skyblock-shopping-list.config.text.craftable_size", d.craftableScale,
                    s -> s.craftableScale, (s, v) -> s.craftableScale = v))
                .option(align("skyblock-shopping-list.config.text.craftable_align", d.craftableAlign, s -> s.craftableAlign,
                    (s, v) -> s.craftableAlign = v))
                .option(size("skyblock-shopping-list.config.text.forging_size", d.forgingScale,
                    s -> s.forgingScale, (s, v) -> s.forgingScale = v))
                .option(align("skyblock-shopping-list.config.text.forging_align", d.forgingAlign, s -> s.forgingAlign,
                    (s, v) -> s.forgingAlign = v))
                .option(size("skyblock-shopping-list.config.text.mob_drops_size", d.mobDropsScale,
                    s -> s.mobDropsScale, (s, v) -> s.mobDropsScale = v))
                .option(align("skyblock-shopping-list.config.text.mob_drops_align", d.mobDropsAlign, s -> s.mobDropsAlign,
                    (s, v) -> s.mobDropsAlign = v))
                .option(toggle("skyblock-shopping-list.config.text.shadow", d.textShadow,
                    s -> s.textShadow, (s, v) -> s.textShadow = v))
                .option(toggle("skyblock-shopping-list.config.text.bold_roots", d.boldRootNames,
                    s -> s.boldRootNames, (s, v) -> s.boldRootNames = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.decoration"))
                .option(toggle("skyblock-shopping-list.config.decoration.marks", d.showMarks,
                    s -> s.showMarks, (s, v) -> s.showMarks = v))
                .option(toggle("skyblock-shopping-list.config.decoration.row_boxes", d.showRowBoxes,
                    s -> s.showRowBoxes, (s, v) -> s.showRowBoxes = v))
                .option(toggle("skyblock-shopping-list.config.decoration.tree_lines", d.showTreeLines,
                    s -> s.showTreeLines, (s, v) -> s.showTreeLines = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.spacing"))
                .option(slider("skyblock-shopping-list.config.spacing.row_height", 10, 24,
                    d.rowHeight, s -> s.rowHeight, (s, v) -> s.rowHeight = v))
                .option(slider("skyblock-shopping-list.config.spacing.row_gap", 0, 6, d.rowGap, s -> s.rowGap,
                    (s, v) -> s.rowGap = v))
                .option(slider("skyblock-shopping-list.config.spacing.title_gap", 0, 12, d.titleGap, s -> s.titleGap,
                    (s, v) -> s.titleGap = v))
                .option(slider("skyblock-shopping-list.config.spacing.indent", 4, 20, d.indent,
                    s -> s.indent, (s, v) -> s.indent = v))
                .option(slider("skyblock-shopping-list.config.spacing.padding", 0, 16, d.padding,
                    s -> s.padding, (s, v) -> s.padding = v))
                .option(slider("skyblock-shopping-list.config.spacing.panel_border", 0, 4, d.panelBorderWidth,
                    s -> s.panelBorderWidth, (s, v) -> s.panelBorderWidth = v))
                .option(slider("skyblock-shopping-list.config.spacing.row_border", 0, 2, d.rowBorderWidth,
                    s -> s.rowBorderWidth, (s, v) -> s.rowBorderWidth = v))
                .option(slider("skyblock-shopping-list.config.spacing.panel_radius", 0, 6,
                    d.panelRadius, s -> s.panelRadius, (s, v) -> s.panelRadius = v))
                .option(slider("skyblock-shopping-list.config.spacing.row_radius", 0, 6,
                    d.rowRadius, s -> s.rowRadius, (s, v) -> s.rowRadius = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.tracking_colours"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.tracking_colours.desc")))
                .option(colour("skyblock-shopping-list.config.colour.done", d.done, s -> s.done, (s, v) -> s.done = v))
                .option(colour("skyblock-shopping-list.config.colour.partial", d.partial, s -> s.partial, (s, v) -> s.partial = v))
                .option(colour("skyblock-shopping-list.config.colour.missing", d.missing, s -> s.missing, (s, v) -> s.missing = v))
                .option(colour("skyblock-shopping-list.config.colour.craftable", d.craftable, s -> s.craftable,
                    (s, v) -> s.craftable = v))
                .option(colour("skyblock-shopping-list.config.colour.cooking", d.cooking, s -> s.cooking, (s, v) -> s.cooking = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.panel_colours"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.panel_colours.desc")))
                .option(colour("skyblock-shopping-list.config.colour.panel_background", d.panelBackground,
                    s -> s.panelBackground, (s, v) -> s.panelBackground = v))
                .option(colour("skyblock-shopping-list.config.colour.panel_border", d.panelBorder, s -> s.panelBorder,
                    (s, v) -> s.panelBorder = v))
                .option(colour("skyblock-shopping-list.config.colour.title_bar", d.headerBackground, s -> s.headerBackground,
                    (s, v) -> s.headerBackground = v))
                .option(colour("skyblock-shopping-list.config.colour.title_text", d.titleText, s -> s.titleText,
                    (s, v) -> s.titleText = v))
                .option(colour("skyblock-shopping-list.config.colour.dividers", d.divider, s -> s.divider, (s, v) -> s.divider = v))
                .option(colour("skyblock-shopping-list.config.colour.row_background", d.rowBackground, s -> s.rowBackground,
                    (s, v) -> s.rowBackground = v))
                .option(colour("skyblock-shopping-list.config.colour.tree_lines", d.treeLines, s -> s.treeLines,
                    (s, v) -> s.treeLines = v))
                .option(colour("skyblock-shopping-list.config.colour.root_text", d.rootText, s -> s.rootText,
                    (s, v) -> s.rootText = v))
                .option(colour("skyblock-shopping-list.config.colour.item_text", d.itemText, s -> s.itemText,
                    (s, v) -> s.itemText = v))
                .option(colour("skyblock-shopping-list.config.colour.section_header", d.sectionHeader, s -> s.sectionHeader,
                    (s, v) -> s.sectionHeader = v))
                .option(colour("skyblock-shopping-list.config.colour.section_text", d.sectionText, s -> s.sectionText,
                    (s, v) -> s.sectionText = v))
                .option(colour("skyblock-shopping-list.config.colour.forging_header", d.forgingHeader, s -> s.forgingHeader,
                    (s, v) -> s.forgingHeader = v))
                .option(colour("skyblock-shopping-list.config.colour.forging_text", d.forgingText, s -> s.forgingText,
                    (s, v) -> s.forgingText = v))
                .option(colour("skyblock-shopping-list.config.colour.forging_other_text", d.forgingOtherText,
                    s -> s.forgingOtherText, (s, v) -> s.forgingOtherText = v))
                .option(colour("skyblock-shopping-list.config.colour.mob_drops_header", d.mobDropsHeader, s -> s.mobDropsHeader,
                    (s, v) -> s.mobDropsHeader = v))
                .option(colour("skyblock-shopping-list.config.colour.mob_drops_text", d.mobDropsText, s -> s.mobDropsText,
                    (s, v) -> s.mobDropsText = v))
                .option(colour("skyblock-shopping-list.config.colour.mob_drops_overdue_text", d.mobDropsOverdueText,
                    s -> s.mobDropsOverdueText, (s, v) -> s.mobDropsOverdueText = v))
                .build())
            .build();
    }

    /** Remembered between screen rebuilds (each preset action reopens the screen). */
    private static String chosenPreset;
    private static String presetName = "My preset";

    /**
     * Presets group: load / save / delete presets and copy / paste share codes. Every action works on the saved
     * look and takes effect right away, then reopens the screen so the other options show the new values.
     */
    private static OptionGroup presets(Screen parent) {
        List<String> names = HudPresets.list();
        if (chosenPreset == null || !names.contains(chosenPreset)) chosenPreset = names.get(0);
        Option<String> chosen = Option.<String>createBuilder()
            .name(Component.translatable("skyblock-shopping-list.config.presets.preset"))
            .description(describe(Component.translatable("skyblock-shopping-list.config.presets.preset.desc",
                HudPresets.BACKUP_NAME)))
            .binding(names.get(0), () -> chosenPreset, v -> chosenPreset = v)
            .controller(opt -> DropdownStringControllerBuilder.create(opt).values(names))
            .build();
        Option<String> name = Option.<String>createBuilder()
            .name(Component.translatable("skyblock-shopping-list.config.presets.name"))
            .description(describe(Component.translatable("skyblock-shopping-list.config.presets.name.desc")))
            .binding("My preset", () -> presetName, v -> presetName = v)
            .controller(StringControllerBuilder::create)
            .build();
        Runnable reopen = () -> Minecraft.getInstance().gui.setScreen(create(parent));
        Component saveFirst = Component.translatable("skyblock-shopping-list.config.presets.save_first");
        return OptionGroup.createBuilder()
            .name(Component.translatable("skyblock-shopping-list.config.presets"))
            .description(describe(Component.translatable("skyblock-shopping-list.config.presets.desc")))
            .option(chosen)
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.presets.load"))
                .text(Component.translatable("skyblock-shopping-list.config.presets.load.button"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.presets.load.desc",
                    HudPresets.BACKUP_NAME)))
                .action((screen, button) -> {
                    chosenPreset = chosen.pendingValue();
                    if (HudPresets.load(chosenPreset)) {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.preset_loaded"), Component.literal(chosenPreset));
                    } else {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.preset_not_found"), Component.literal(chosenPreset));
                    }
                    reopen.run();
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.presets.delete"))
                .text(Component.translatable("skyblock-shopping-list.config.presets.delete.button"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.presets.delete.desc")))
                .action((screen, button) -> {
                    String target = chosen.pendingValue();
                    if (HudPresets.delete(target)) {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.preset_deleted"), Component.literal(target));
                        chosenPreset = null;
                    } else {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.cant_delete"), Component.literal(target));
                    }
                    reopen.run();
                })
                .build())
            .option(name)
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.presets.save"))
                .text(Component.translatable("skyblock-shopping-list.config.presets.save.button"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.presets.save.desc").append(saveFirst)))
                .action((screen, button) -> {
                    presetName = name.pendingValue();
                    try {
                        chosenPreset = HudPresets.save(presetName);
                    } catch (IOException e) {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.preset_not_saved"),
                            Component.translatable("skyblock-shopping-list.toast.preset_not_saved.message"));
                        return;
                    }
                    HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.preset_saved"), Component.literal(chosenPreset));
                    reopen.run();
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.presets.copy"))
                .text(Component.translatable("skyblock-shopping-list.config.presets.copy.button"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.presets.copy.desc").append(saveFirst)))
                .action((screen, button) -> {
                    HudPresets.copyCode();
                    HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.code_copied"),
                        Component.translatable("skyblock-shopping-list.toast.code_copied.message"));
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.translatable("skyblock-shopping-list.config.presets.paste"))
                .text(Component.translatable("skyblock-shopping-list.config.presets.paste.button"))
                .description(describe(Component.translatable("skyblock-shopping-list.config.presets.paste.desc",
                    HudPresets.BACKUP_NAME)))
                .action((screen, button) -> {
                    String imported;
                    try {
                        imported = HudPresets.pasteCode();
                    } catch (IOException e) {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.preset_not_saved"),
                            Component.translatable("skyblock-shopping-list.toast.preset_not_saved.message"));
                        return;
                    }
                    if (imported == null) {
                        HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.no_code"),
                            Component.translatable("skyblock-shopping-list.toast.no_code.message", ShoppingList.NAME));
                        return;
                    }
                    chosenPreset = imported;
                    HudPresets.toast(Component.translatable("skyblock-shopping-list.toast.code_loaded"), Component.literal(imported));
                    reopen.run();
                })
                .build())
            .build();
    }

    /** While Appearance is being built: the copy the preview draws, and the preview itself. */
    private static HudStyle previewDraft;
    private static ImageRenderer previewImage;

    /** Description text, with the live preview above it while building Appearance. */
    private static OptionDescription describe(Component text) {
        OptionDescription.Builder builder = OptionDescription.createBuilder().text(text);
        if (previewImage != null) builder.customImage(previewImage);
        return builder.build();
    }

    /**
     * Finishes an Appearance option: the binding reads and writes the saved look (applied on Save), and every
     * unsaved change is copied to the preview's draft straight away.
     */
    private static <T> Option<T> styled(Option.Builder<T> builder, T def, Function<HudStyle, T> get,
                                        BiConsumer<HudStyle, T> set) {
        HudStyle style = HudStyle.get();
        HudStyle draft = previewDraft;
        builder.binding(def, () -> get.apply(style), v -> set.accept(style, v));
        if (draft != null) {
            builder.addListener((option, event) -> {
                if (event == OptionEventListener.Event.STATE_CHANGE) set.accept(draft, option.pendingValue());
            });
        }
        return builder.build();
    }

    private static Option<Float> size(String key, float def, Function<HudStyle, Float> get,
                                      BiConsumer<HudStyle, Float> set) {
        return styled(Option.<Float>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable(key + ".desc")))
            .controller(opt -> FloatSliderControllerBuilder.create(opt).range(0.5f, 2.0f).step(0.05f)),
            def, get, set);
    }

    private static Option<HudStyle.Align> align(String key, HudStyle.Align def, Function<HudStyle, HudStyle.Align> get,
                                                BiConsumer<HudStyle, HudStyle.Align> set) {
        return styled(Option.<HudStyle.Align>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable("skyblock-shopping-list.config.align.desc")))
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Align.class)
                .formatValue(v -> Component.translatable(switch (v) {
                    case LEFT -> "skyblock-shopping-list.config.align.left";
                    case CENTRE -> "skyblock-shopping-list.config.align.centre";
                    case RIGHT -> "skyblock-shopping-list.config.align.right";
                }))),
            def, get, set);
    }

    private static Option<HudStyle.Placement> placement(String key, HudStyle.Placement def,
                                                        Function<HudStyle, HudStyle.Placement> get,
                                                        BiConsumer<HudStyle, HudStyle.Placement> set) {
        return styled(Option.<HudStyle.Placement>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable("skyblock-shopping-list.config.placement.desc")))
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Placement.class)
                .formatValue(v -> Component.translatable(v == HudStyle.Placement.OWN_PANEL
                    ? "skyblock-shopping-list.config.placement.own_panel" : "skyblock-shopping-list.config.placement.main_panel"))),
            def, get, set);
    }

    private static Option<HudStyle.ProgressBars> progressBars(String key, HudStyle.ProgressBars def,
                                                              Function<HudStyle, HudStyle.ProgressBars> get,
                                                              BiConsumer<HudStyle, HudStyle.ProgressBars> set) {
        return styled(Option.<HudStyle.ProgressBars>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable(key + ".desc")))
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.ProgressBars.class)
                .formatValue(v -> Component.translatable(switch (v) {
                    case OFF -> "skyblock-shopping-list.config.progress_bars.off";
                    case TOP_LEVEL -> "skyblock-shopping-list.config.progress_bars.top_level";
                    case FIRST_LEVEL -> "skyblock-shopping-list.config.progress_bars.first_level";
                    case ALL -> "skyblock-shopping-list.config.progress_bars.all";
                }))),
            def, get, set);
    }

    private static Option<Color> colour(String key, int def, ToIntFunction<HudStyle> get, ObjIntConsumer<HudStyle> set) {
        return styled(Option.<Color>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable("skyblock-shopping-list.config.colour.desc",
                I18n.get(key).toLowerCase(Locale.ROOT))))
            .controller(opt -> ColorControllerBuilder.create(opt).allowAlpha(true)),
            new Color(def, true), s -> new Color(get.applyAsInt(s), true), (s, c) -> set.accept(s, c.getRGB()));
    }

    private static Option<Integer> slider(String key, int min, int max, int def,
                                          Function<HudStyle, Integer> get, BiConsumer<HudStyle, Integer> set) {
        return styled(Option.<Integer>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable(key + ".desc")))
            .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(1)),
            def, get, set);
    }

    private static Option<Boolean> toggle(String key, boolean def, Function<HudStyle, Boolean> get,
                                          BiConsumer<HudStyle, Boolean> set) {
        return styled(Option.<Boolean>createBuilder()
            .name(Component.translatable(key))
            .description(describe(Component.translatable(key + ".desc")))
            .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true)),
            def, get, set);
    }

    /** Where one notification type shows: Off, Chat, Toast or Both. */
    private static Option<Notifier.Channel> channel(SandboxWidget widget, Notifier.Type type, String key) {
        return Option.<Notifier.Channel>createBuilder()
            .name(Component.translatable(key))
            .description(OptionDescription.of(Component.translatable(key + ".desc")
                .append(Component.translatable("skyblock-shopping-list.config.notifications.channel.desc"))))
            .binding(type.defaultChannel, () -> widget.getNotifyChannel(type), v -> widget.setNotifyChannel(type, v))
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Notifier.Channel.class)
                .formatValue(v -> Component.translatable(switch (v) {
                    case OFF -> "skyblock-shopping-list.config.notifications.channel.off";
                    case CHAT -> "skyblock-shopping-list.config.notifications.channel.chat";
                    case TOAST -> "skyblock-shopping-list.config.notifications.channel.toast";
                    case BOTH -> "skyblock-shopping-list.config.notifications.channel.both";
                })))
            .build();
    }

    /** A widget setting; {@code def} is what the reset button sets, the same as the widget's own default. */
    private static Option<Boolean> toggle(String key, boolean def, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
            .name(Component.translatable(key))
            .description(OptionDescription.of(Component.translatable(key + ".desc")))
            .binding(def, getter, setter)
            .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
            .build();
    }
}
