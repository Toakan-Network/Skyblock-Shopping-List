package uk.toakan.tad.skyblockshoppinglist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The current mayor's and minister's perks, read from Hypixel's public election resource at most once an hour, and
 * the events they bring that some drops need: a Fishing Festival for shark drops, a Mining Fiesta for Refined
 * Mineral and Glossy Gemstone, Diana's Mythological Ritual for her mobs' drops. Foxy's Extra Event perk adds one
 * of these too. {@link ForgeSpeed} takes Cole's Molten Forge from the same request.
 */
public final class MayorEvents {
    private static final String ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election";
    private static final long REFRESH_MS = 60 * 60 * 1000L;
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

    /** SkyBlock calendar, as SkyblockAPI's SkyBlockInstant counts it: a month is 31 days of 20 minutes. */
    static final long SKYBLOCK_EPOCH = 1559829300000L;
    static final long MONTH_MS = 37_200_000L;
    static final long DAY_MS = 1_200_000L;
    /** The Fishing Festival runs on days 1 to 3 of every month (seen in game: 1 hour from the month's start). */
    private static final int FESTIVAL_DAYS = 3;
    /** A festival started in chat counts as running at most this long, in case its end line is missed. */
    private static final long FESTIVAL_MAX_MS = FESTIVAL_DAYS * DAY_MS + 5 * 60_000L;
    private static final String FESTIVAL_STARTED = "FISHING FESTIVAL The festival is now underway!";
    private static final String FESTIVAL_ENDED = "FISHING FESTIVAL The festival has concluded!";

    /** Events some drops can only be farmed in, by the perk that brings them. */
    public enum Event {
        FISHING_FESTIVAL("Fishing Festival", "skyblock-shopping-list.event.fishing_festival"),
        MINING_FIESTA("Mining Fiesta", "skyblock-shopping-list.event.mining_fiesta"),
        MYTHOLOGICAL_RITUAL("Mythological Ritual", "skyblock-shopping-list.event.mythological_ritual");

        /** The perk's name in the election resource (and SkyblockAPI's MayorPerk). */
        final String perk;
        final String labelKey;

        Event(String perk, String labelKey) {
            this.perk = perk;
            this.labelKey = labelKey;
        }

        public String label() {
            return I18n.get(labelKey);
        }
    }

    /** Saved to mayor.json, so a restart within the hour doesn't ask again. */
    private static final class Data {
        long checkedAt;
        /** Perk names of the mayor and the minister. */
        List<String> perks = List.of();
        /** Events Foxy's Extra Event perk names in its description. */
        List<String> extraEvents = List.of();
    }

    private static volatile Data data;
    private static volatile long version = 0;
    /** When a Fishing Festival start line was last read (0: none, or it ended). */
    private static volatile long festivalSeenAt = 0;
    /** {@link #gatedBy(String)} per item name, for the drop table version {@link #gatedVersion}. */
    private static final Map<String, Optional<Event>> GATED = new ConcurrentHashMap<>();
    private static volatile long gatedVersion = -1;

    private MayorEvents() {}

    public static void register() {
        // Every message fires exactly one of these: hidden by another mod or shown.
        ClientReceiveMessageEvents.GAME.register(MayorEvents::read);
        ClientReceiveMessageEvents.GAME_CANCELED.register(MayorEvents::read);
    }

    private static void read(Component message, boolean overlay) {
        if (overlay || !SkyblockDetector.isTracking()) return;
        String text = ItemNames.clean(message.getString());
        if (text.startsWith(FESTIVAL_STARTED)) {
            festivalSeenAt = System.currentTimeMillis();
            version++;
        } else if (text.startsWith(FESTIVAL_ENDED)) {
            festivalSeenAt = 0;
            version++;
        }
    }

    /** Changes whenever the perks or a running event change. */
    public static long getVersion() {
        return version;
    }

    /** Starts a background check of the election when the last one is over an hour old and something uses it. */
    public static void refreshIfDue() {
        boolean wanted = HudStyle.get().forgeTimes != HudStyle.ForgeTimes.OFF
            || HudStyle.get().eventDrops != HudStyle.EventDrops.OFF;
        if (!wanted || !SkyblockDetector.isOnSkyblock()) return;
        if (System.currentTimeMillis() - get().checkedAt < REFRESH_MS) return;
        if (!FETCHING.compareAndSet(false, true)) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(ELECTION_URL)).timeout(Duration.ofSeconds(10)).GET().build();
        Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            try {
                // Failures are retried at the next hourly check; the last known perks stay in use.
                Data next = new Data();
                Data last = get();
                next.perks = last.perks;
                next.extraEvents = last.extraEvents;
                if (error == null && response.statusCode() / 100 == 2) {
                    try {
                        JsonObject mayor = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("mayor");
                        Perks perks = readPerks(mayor);
                        next.perks = perks.perks();
                        next.extraEvents = perks.extraEvents();
                        ForgeSpeed.setMayorBonus(ForgeSpeed.moltenForgeBonus(mayor));
                        ShoppingList.debug("Mayor perks: {}, extra events: {}", next.perks, next.extraEvents);
                    } catch (RuntimeException e) {
                        // Unreadable or changed JSON: still counts as a check, or it would be retried every second.
                        ShoppingList.LOGGER.warn("Mayor check: could not read the election data: {}", e.toString());
                    }
                } else {
                    ShoppingList.LOGGER.warn("Mayor check failed: {}", error != null ? error.toString() : "HTTP " + response.statusCode());
                }
                next.checkedAt = System.currentTimeMillis();
                data = next;
                version++;
                JsonFiles.writeAsync(FilePathManager.MAYOR_JSON, () -> next);
            } catch (RuntimeException e) {
                ShoppingList.LOGGER.warn("Mayor check failed: {}", e.toString());
            } finally {
                FETCHING.set(false);
            }
        });
    }

    /** Perk names, and the events Foxy's Extra Event schedules. */
    record Perks(List<String> perks, List<String> extraEvents) {}

    /** The mayor's perks and the minister's one perk; Foxy's Extra Event names its event in the description. */
    static Perks readPerks(JsonObject mayor) {
        Set<String> perks = new LinkedHashSet<>();
        Set<String> extra = new LinkedHashSet<>();
        if (mayor != null) {
            JsonArray list = mayor.getAsJsonArray("perks");
            if (list != null) for (JsonElement perk : list) addPerk(perk.getAsJsonObject(), perks, extra);
            JsonObject minister = mayor.getAsJsonObject("minister");
            if (minister != null && minister.has("perk")) addPerk(minister.getAsJsonObject("perk"), perks, extra);
        }
        return new Perks(List.copyOf(perks), List.copyOf(extra));
    }

    private static void addPerk(JsonObject perk, Set<String> perks, Set<String> extra) {
        if (perk == null || !perk.has("name")) return;
        String name = perk.get("name").getAsString();
        perks.add(name);
        if (!name.equals("Extra Event") || !perk.has("description")) return;
        String description = ItemNames.clean(perk.get("description").getAsString());
        for (Event event : Event.values()) {
            if (description.contains(event.perk)) extra.add(event.perk);
        }
    }

    /** Whether this term has the event at all: the mayor's or minister's perk, or Foxy's Extra Event. */
    public static boolean perkActive(Event event) {
        Data d = get();
        return d.perks.contains(event.perk) || d.extraEvents.contains(event.perk);
    }

    /**
     * Whether the event is on right now. A Fishing Festival is on during days 1 to 3 of each month while the perk is
     * active, or after its start line in chat until its end line (Foxy's extra festival comes at other times). The
     * Mythological Ritual lasts the whole term. A Mining Fiesta's times aren't known yet, so it counts as on while
     * its perk is active.
     */
    public static boolean running(Event event, long now) {
        if (event == Event.FISHING_FESTIVAL) {
            long seen = festivalSeenAt;
            if (seen > 0 && now - seen < FESTIVAL_MAX_MS) return true;
            return get().perks.contains(event.perk) && festivalDay(now);
        }
        return perkActive(event);
    }

    /** Days 1 to 3 of a SkyBlock month. */
    static boolean festivalDay(long now) {
        long intoMonth = Math.floorMod(now - SKYBLOCK_EPOCH, MONTH_MS);
        return intoMonth < FESTIVAL_DAYS * DAY_MS;
    }

    /** The event {@code item} can be farmed in now, per Settings > Rows > Event drops, or null. */
    public static Event farmableNow(String item) {
        HudStyle.EventDrops mode = HudStyle.get().eventDrops;
        if (mode == HudStyle.EventDrops.OFF) return null;
        Event event = gatedBy(item);
        if (event == null) return null;
        boolean on = mode == HudStyle.EventDrops.PERK_ACTIVE ? perkActive(event) : running(event, System.currentTimeMillis());
        return on ? event : null;
    }

    /** The event every source of {@code item} needs, or null when it can be had without one. */
    static Event gatedBy(String item) {
        long tableVersion = MobDrops.getVersion();
        if (tableVersion != gatedVersion) {
            GATED.clear();
            gatedVersion = tableVersion;
        }
        return GATED.computeIfAbsent(ItemNames.normalize(item),
            key -> Optional.ofNullable(gatedBy(item, MobDrops.sources(item)))).orElse(null);
    }

    /**
     * The event {@code item} needs: Refined Mineral and Glossy Gemstone drop only in a Mining Fiesta; otherwise the
     * event whose mobs are every one of its sources (sharks: Fishing Festival, Diana's mobs: Mythological Ritual).
     * An item with any other source, or none, needs no event.
     */
    static Event gatedBy(String item, List<MobDrops.Source> sources) {
        String name = ItemNames.normalize(item);
        if (name.equals(ItemNames.normalize("Refined Mineral")) || name.equals(ItemNames.normalize("Glossy Gemstone"))) {
            return Event.MINING_FIESTA;
        }
        if (sources.isEmpty()) return null;
        Event needed = null;
        for (MobDrops.Source source : sources) {
            Event event = mobEvent(source.mob);
            if (event == null || (needed != null && needed != event)) return null;
            needed = event;
        }
        return needed;
    }

    /** Sharks only spawn in a Fishing Festival, Diana's mobs only from her burrows. */
    static Event mobEvent(String mob) {
        String name = MobDrops.baseName(mob).toLowerCase(Locale.ROOT);
        for (String shark : List.of("blue shark", "nurse shark", "tiger shark", "great white shark")) {
            if (name.equals(shark)) return Event.FISHING_FESTIVAL;
        }
        for (String diana : List.of("minos", "minotaur", "gaia construct", "siamese lynx", "cretan bull",
                "stranded nymph", "harpy", "sphinx", "manticore")) {
            if (name.contains(diana)) return Event.MYTHOLOGICAL_RITUAL;
        }
        return null;
    }

    private static Data get() {
        Data d = data;
        if (d != null) return d;
        synchronized (MayorEvents.class) {
            if (data == null) {
                Data read = JsonFiles.read(FilePathManager.MAYOR_JSON, Data.class);
                if (read == null) read = new Data();
                if (read.perks == null) read.perks = List.of();
                if (read.extraEvents == null) read.extraEvents = List.of();
                data = read;
            }
            return data;
        }
    }
}
