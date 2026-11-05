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
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RadiantAlertClient implements ClientModInitializer {

    // ---- Settings: change these to taste ----
    private static final boolean ALERT_RADIANT = true;
    private static final boolean ALERT_SHINY = true;
    private static final boolean ALERT_SPECIES = true;
    private static final boolean ALERT_WORMHOLE = true;

    /** Aspect names that mark a Pokémon as Radiant. Use /radiantalert debug to check what Delta uses. */
    private static final Set<String> RADIANT_ASPECTS = Set.of("radiant");

    /** Species to alert on (lowercase). */
    private static final Set<String> WATCHED_SPECIES = Set.of("lunala", "solgaleo");

    /** Wormhole entities: alert if the entity type id contains one of these words. */
    private static final Set<String> WORMHOLE_ENTITY_WORDS = Set.of("wormhole");

    /** Wormhole chat announcements: message must contain one of these words AND the word "open". */
    private static final Set<String> WORMHOLE_CHAT_WORDS = Set.of("wormhole");

    /** Ignore wormholes below this tier (1 = alert for all). Messages look like "A tier 2 Ultra Wormhole has opened nearby!" */
    private static final int MIN_WORMHOLE_TIER = 1;
    private static final Pattern TIER_PATTERN = Pattern.compile("tier\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    // -----------------------------------------

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;
    private static final long CHAT_COOLDOWN_MS = 3000;

    private record Pending(PokemonEntity entity, int[] ticks) {}

    private final List<Pending> pending = new ArrayList<>();
    private final Set<UUID> alerted = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> e) { return size() > MAX_REMEMBERED; }
    });
    private long lastChatAlert = 0;

    @Override
    public void onInitializeClient() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe) {
                if (!alerted.contains(pe.getUuid())) pending.add(new Pending(pe, new int[]{0}));
            } else if (ALERT_WORMHOLE && isWormholeEntity(entity)) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null && alerted.add(entity.getUuid())) announceWormholeEntity(client, entity);
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
        if (client.player == null || client.world == null) { pending.clear(); return; }

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

    private static boolean isRadiant(Pokemon pokemon) {
        for (String aspect : pokemon.getAspects()) {
            if (RADIANT_ASPECTS.contains(aspect.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isWatchedSpecies(Pokemon pokemon) {
        return WATCHED_SPECIES.contains(pokemon.getSpecies().getName().toLowerCase(Locale.ROOT));
    }

    private static boolean isWormholeEntity(Entity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        String s = id.toString().toLowerCase(Locale.ROOT);
        for (String word : WORMHOLE_ENTITY_WORDS) {
            if (s.contains(word)) return true;
        }
        return false;
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

    private void announceWormholeEntity(MinecraftClient client, Entity entity) {
        long dist = Math.round(client.player.distanceTo(entity));
        MutableText msg = Text.literal("✦ Wormhole spawned! ").formatted(Formatting.RED, Formatting.BOLD)
            .append(Text.literal(String.format("at %d, %d, %d (%d blocks away)",
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), dist)).formatted(Formatting.GRAY));
        client.player.sendMessage(msg, false);
        client.player.playSound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);
    }

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
    }

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

    /** Lists every entity type within 64 blocks (except players, items, XP orbs and Pokémon), with counts and nearest distance. */
    private void listEntities(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        Map<String, int[]> found = new TreeMap<>(); // id -> {count, nearest distance in blocks}
        for (Entity e : client.world.getEntities()) {
            double d = Math.sqrt(e.squaredDistanceTo(client.player));
            if (d > 64) continue;
            String id = Registries.ENTITY_TYPE.getId(e.getType()).toString();
            if (id.equals("minecraft:player") || id.equals("minecraft:item")
                    || id.equals("minecraft:experience_orb") || id.equals("cobblemon:pokemon")) continue;
            int[] v = found.computeIfAbsent(id, k -> new int[]{0, Integer.MAX_VALUE});
            v[0]++;
            v[1] = Math.min(v[1], (int) Math.round(d));
        }
        if (found.isEmpty()) {
            client.player.sendMessage(Text.literal("[RadiantAlert] No entities (other than players, items and Pokémon) within 64 blocks."), false);
            return;
        }
        client.player.sendMessage(Text.literal("[RadiantAlert] Entities within 64 blocks:"), false);
        for (Map.Entry<String, int[]> en : found.entrySet()) {
            client.player.sendMessage(Text.literal(" - " + en.getKey() + " x" + en.getValue()[0]
                + " (nearest " + en.getValue()[1] + " blocks)"), false);
        }
    }
}
