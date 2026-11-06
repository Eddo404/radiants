package dev.radiantalert;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RadiantAlertClient implements ClientModInitializer {

    // ---------------- Settings: change these to taste ----------------
    private static final boolean ALERT_RADIANT = true;
    private static final boolean ALERT_SHINY = true;
    private static final boolean ALERT_SPECIES = true;
    private static final boolean ALERT_WORMHOLE = true;

    /** Aspect names that mark a Pokémon as Radiant. Use /radiantalert debug to check what Delta uses. */
    private static final Set<String> RADIANT_ASPECTS = Set.of("radiant");

    /** Species to alert on (lowercase). */
    private static final Set<String> WATCHED_SPECIES = Set.of("lunala", "solgaleo");

    /** Wormhole entities: alert straight away if the entity type id contains one of these words. */
    private static final Set<String> WORMHOLE_ENTITY_WORDS = Set.of("wormhole");

    /** Wormhole chat announcements: message must contain one of these words AND the word "open". */
    private static final Set<String> WORMHOLE_CHAT_WORDS = Set.of("wormhole");

    /** Ignore wormholes below this tier (1 = alert for all). Messages look like "A tier 2 Ultra Wormhole has opened nearby!" */
    private static final int MIN_WORMHOLE_TIER = 1;
    private static final Pattern TIER_PATTERN = Pattern.compile("tier\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** How long (ms) before/after a wormhole chat message that newly loaded entities count as "possible wormhole". */
    private static final long WINDOW_MS = 6000;
    /** Max number of "possible wormhole" coordinate lines per wormhole message. */
    private static final int MAX_CANDIDATES = 5;
    // -----------------------------------------------------------------

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;
    private static final long CHAT_COOLDOWN_MS = 3000;

    /** Entities that are never a wormhole, so they are not listed as candidates. */
    private static final Set<String> IGNORED_ENTITY_IDS = Set.of(
        "minecraft:player", "minecraft:item", "minecraft:experience_orb", "minecraft:arrow",
        "minecraft:spectral_arrow", "minecraft:trident", "minecraft:snowball", "minecraft:egg",
        "minecraft:ender_pearl", "minecraft:firework_rocket", "minecraft:falling_block",
        "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting",
        "minecraft:fishing_bobber", "cobblemon:pokemon");

    private record Pending(PokemonEntity entity, int[] ticks) {}
    private record Recent(Entity entity, long time) {}

    private final List<Pending> pending = new ArrayList<>();
    private final Deque<Recent> recent = new ArrayDeque<>();
    private final Set<UUID> alerted = cappedSet(MAX_REMEMBERED);
    private final Set<UUID> candidatesSeen = cappedSet(MAX_REMEMBERED);

    private long lastChatAlert = 0;
    private long wormholeWindowEnd = 0;
    private int candidatesReported = 0;

    private static Set<UUID> cappedSet(int max) {
        return Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > max;
            }
        });
    }

    @Override
    public void onInitializeClient() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe) {
                if (!alerted.contains(pe.getUuid())) pending.add(new Pending(pe, new int[]{0}));
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || !ALERT_WORMHOLE) return;

            String id = entityId(entity);
            if (isWormholeEntityId(id)) {
                if (alerted.add(entity.getUuid())) announceWormholeEntity(client, entity);
                return;
            }

            // Not obviously a wormhole: remember it briefly. If a wormhole chat message shows up
            // around the same time, we report these as "possible wormhole" with coordinates.
            if (!IGNORED_ENTITY_IDS.contains(id)) {
                long now = System.currentTimeMillis();
                recent.addLast(new Recent(entity, now));
                while (recent.size() > 100 || (!recent.isEmpty() && now - recent.peekFirst().time() > WINDOW_MS)) {
                    recent.removeFirst();
                }
                if (now < wormholeWindowEnd) reportCandidate(client, entity);
            }
        });

        // Wormhole announcements: servers send these either as system messages or as chat messages
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleWormholeChat(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
            handleWormholeChat(message.getString()));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("radiantalert")
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debugNearest(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("entities").executes(ctx -> {
                    listEntities(MinecraftClient.getInstance());
                    return 1;
                }))));
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { pending.clear(); recent.clear(); return; }

        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            PokemonEntity e = p.entity();

            if (e.isRemoved() || ++p.ticks()[0] > MAX_WAIT_TICKS) { it.remove(); continue; }
            if (e.getOwnerUuid() != null) { it.remove(); continue; } // someone's own Pokémon

            // Species is known immediately; aspects/shiny may take a few ticks to sync.
            // Keep waiting until the radiant tag shows up or time runs out, so a
            // Radiant Lunala isn't announced as just "Lunala" before the tag arrives.
            Pokemon pokemon = e.getPokemon();
            boolean radiant = ALERT_RADIANT && isRadiant(pokemon);
            boolean shiny = ALERT_SHINY && pokemon.getShiny();
            boolean species = ALERT_SPECIES && isWatchedSpecies(pokemon);

            boolean waitedLongEnough = p.ticks()[0] >= 10;
            if (radiant || (waitedLongEnough && (shiny || species))) {
                it.remove();
                if (alerted.add(e.getUuid())) announce(client, e, radiant, shiny, species);
            }
        }
    }

    // ------------------------- Pokémon alerts -------------------------

    private static boolean isRadiant(Pokemon pokemon) {
        for (String aspect : pokemon.getAspects()) {
            if (RADIANT_ASPECTS.contains(aspect.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isWatchedSpecies(Pokemon pokemon) {
        return WATCHED_SPECIES.contains(pokemon.getSpecies().getName().toLowerCase(Locale.ROOT));
    }

    private void announce(MinecraftClient client, PokemonEntity e, boolean radiant, boolean shiny, boolean species) {
        List<String> tags = new ArrayList<>();
        if (radiant) tags.add("Radiant");
        if (shiny) tags.add("Shiny");
        if (species) tags.add("Legendary");

        Formatting color = radiant ? Formatting.AQUA : shiny ? Formatting.YELLOW : Formatting.LIGHT_PURPLE;
        long dist = Math.round(client.player.distanceTo(e));

        MutableText msg = Text.literal("✦ " + String.join(" + ", tags) + " spawned! ").formatted(color, Formatting.BOLD)
            .append(Text.empty().append(e.getPokemon().getSpecies().getTranslatedName()).formatted(Formatting.WHITE))
            .append(Text.literal(String.format(" at %d, %d, %d (%d blocks away)",
                    e.getBlockX(), e.getBlockY(), e.getBlockZ(), dist)).formatted(Formatting.GRAY));
        client.player.sendMessage(msg, false);
        client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }

    // ------------------------- Wormhole alerts -------------------------

    private static String entityId(Entity entity) {
        return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
    }

    private static boolean isWormholeEntityId(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        for (String word : WORMHOLE_ENTITY_WORDS) {
            if (s.contains(word)) return true;
        }
        return false;
    }

    /** Entity whose type id says "wormhole": exact coordinates. */
    private void announceWormholeEntity(MinecraftClient client, Entity entity) {
        long dist = Math.round(client.player.distanceTo(entity));
        MutableText msg = Text.literal("✦ Wormhole spawned! ").formatted(Formatting.RED, Formatting.BOLD)
            .append(Text.literal(String.format("at %d, %d, %d (%d blocks away)",
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), dist)).formatted(Formatting.GRAY));
        client.player.sendMessage(msg, false);
        client.player.playSound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);
    }

    /** Server chat message announcing a wormhole. Then looks for entities that loaded around the same time. */
    private void handleWormholeChat(String text) {
        if (!ALERT_WORMHOLE || text.startsWith("✦")) return; // ignore our own alerts
        String lower = text.toLowerCase(Locale.ROOT);
        boolean match = false;
        for (String word : WORMHOLE_CHAT_WORDS) {
            if (lower.contains(word)) { match = true; break; }
        }
        if (!match || !lower.contains("open")) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Matcher m = TIER_PATTERN.matcher(text);
        int tier = m.find() ? Integer.parseInt(m.group(1)) : -1;
        if (tier != -1 && tier < MIN_WORMHOLE_TIER) return;

        long now = System.currentTimeMillis();
        if (now - lastChatAlert < CHAT_COOLDOWN_MS) return;
        lastChatAlert = now;

        String label = tier == -1 ? "Wormhole" : "Tier " + tier + " Wormhole";
        MutableText msg = Text.literal("✦ " + label + " opened nearby!").formatted(Formatting.RED, Formatting.BOLD);
        client.player.sendMessage(msg, false);
        client.player.playSound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);

        // Entities that loaded just before the message, and any that load in the next few seconds
        wormholeWindowEnd = now + WINDOW_MS;
        candidatesReported = 0;
        for (Recent r : new ArrayList<>(recent)) {
            if (now - r.time() <= WINDOW_MS && !r.entity().isRemoved()) reportCandidate(client, r.entity());
        }
    }

    private void reportCandidate(MinecraftClient client, Entity entity) {
        if (candidatesReported >= MAX_CANDIDATES) return;
        if (!candidatesSeen.add(entity.getUuid())) return;
        candidatesReported++;
        long dist = Math.round(client.player.distanceTo(entity));
        client.player.sendMessage(Text.literal(String.format("   ↳ possible wormhole: %s at %d, %d, %d (%d blocks away)",
                entityId(entity), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), dist))
            .formatted(Formatting.GOLD), false);
    }

    // ------------------------- Debug commands -------------------------

    private void debugNearest(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        PokemonEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PokemonEntity pe : client.world.getEntitiesByClass(
                PokemonEntity.class, client.player.getBoundingBox().expand(48), x -> true)) {
            double d = pe.squaredDistanceTo(client.player);
            if (d < best) { best = d; nearest = pe; }
        }
        if (nearest == null) {
            client.player.sendMessage(Text.literal("[RadiantAlert] No Pokémon within 48 blocks."), false);
            return;
        }
        Pokemon p = nearest.getPokemon();
        client.player.sendMessage(Text.literal("[RadiantAlert] " + p.getSpecies().getName()
            + " | shiny=" + p.getShiny() + " | aspects=" + p.getAspects()
            + " | radiant=" + isRadiant(p)), false);
    }

    /** Lists every entity type within 64 blocks (except players, items, XP orbs and Pokémon), with counts, nearest distance and coordinates. */
    private void listEntities(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        Map<String, int[]> found = new TreeMap<>(); // id -> {count, nearest dist, x, y, z of nearest}
        for (Entity e : client.world.getEntities()) {
            int d = (int) Math.round(Math.sqrt(e.squaredDistanceTo(client.player)));
            if (d > 64) continue;
            String id = entityId(e);
            if (id.equals("minecraft:player") || id.equals("minecraft:item")
                    || id.equals("minecraft:experience_orb") || id.equals("cobblemon:pokemon")) continue;
            int[] v = found.computeIfAbsent(id, k -> new int[]{0, Integer.MAX_VALUE, 0, 0, 0});
            v[0]++;
            if (d < v[1]) { v[1] = d; v[2] = e.getBlockX(); v[3] = e.getBlockY(); v[4] = e.getBlockZ(); }
        }
        if (found.isEmpty()) {
            client.player.sendMessage(Text.literal("[RadiantAlert] No entities (other than players, items and Pokémon) within 64 blocks."), false);
            return;
        }
        client.player.sendMessage(Text.literal("[RadiantAlert] Entities within 64 blocks:"), false);
        for (Map.Entry<String, int[]> en : found.entrySet()) {
            int[] v = en.getValue();
            client.player.sendMessage(Text.literal(String.format(" - %s x%d (nearest %d blocks, at %d, %d, %d)",
                en.getKey(), v[0], v[1], v[2], v[3], v[4])), false);
        }
    }
}
