package dev.radiantalert;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class RadiantAlertClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("radiantalert");

    // ---------------- Settings: change these to taste ----------------
    private static final boolean ALERT_RADIANT = true;
    private static final boolean ALERT_SHINY = true;
    private static final boolean ALERT_SPECIES = true;
    private static final boolean ALERT_WORMHOLE = true;

    /** Aspect names that mark a Pokémon as Radiant. Use /radiantalert debug to check what Delta uses. */
    private static final Set<String> RADIANT_ASPECTS = Set.of("radiant");

    /** Species to alert on (lowercase). */
    private static final Set<String> WATCHED_SPECIES = Set.of("lunala", "solgaleo");

    /** Wormhole as an entity: alert if the entity type id contains one of these words. */
    private static final Set<String> WORMHOLE_ENTITY_WORDS = Set.of("wormhole");

    /**
     * Wormhole as a server packet (particle effect etc.): alert if a packet's id or any of its
     * text/identifier values contains one of these words. Use /radiantalert record to find the real
     * name, then add it here (lowercase).
     */
    private static final Set<String> WORMHOLE_PACKET_WORDS = Set.of("wormhole");

    /** One wormhole sends many packets: ignore repeats within this distance (blocks) and time (ms). */
    private static final double DEDUPE_RADIUS = 12;
    private static final long DEDUPE_MS = 30000;
    // -----------------------------------------------------------------

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;
    private static final long RECORD_MS = 120000;
    private static final int RECORD_CHAT_LINES = 25;
    private static final int RECORD_FILE_LINES = 2000;
    private static final String SPY_NAME = "radiantalert_spy";

    private record Pending(PokemonEntity entity, int[] ticks) {}
    private record Info(String id, List<String> strings, double[] pos, int count) {}
    private record Seen(double x, double y, double z, long time) {}

    private final List<Pending> pending = new ArrayList<>();
    private final Set<UUID> alerted = cappedSet(MAX_REMEMBERED);
    private final List<Seen> wormholesSeen = new ArrayList<>();

    private volatile boolean spyInstalled = false;
    private volatile long recordUntil = 0;
    private final Set<String> recordSeen = new HashSet<>();
    private int recordShown = 0;
    private int recordWritten = 0;
    private long lastUnknownPosAlert = 0;

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
        // --- Entities: Pokémon go to the watch list, wormhole-named entities alert immediately ---
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe) {
                if (!alerted.contains(pe.getUuid())) pending.add(new Pending(pe, new int[]{0}));
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || !ALERT_WORMHOLE) return;
            if (containsAny(entityId(entity), WORMHOLE_ENTITY_WORDS) && alerted.add(entity.getUuid())) {
                announceWormhole(client, entity.getX(), entity.getY(), entity.getZ(), entityId(entity));
            }
        });

        // --- Packets: watch what the server sends (particle effects, custom packets) ---
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> installPacketSpy(handler));

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
                }))
                .then(ClientCommandManager.literal("record").executes(ctx -> {
                    toggleRecord(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player != null) c.player.sendMessage(Text.literal("[RadiantAlert] packet watcher installed: " + spyInstalled
                        + " | recording: " + (System.currentTimeMillis() < recordUntil)), false);
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

    private static boolean containsAny(String text, Set<String> words) {
        String s = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    private void announceWormhole(MinecraftClient client, double x, double y, double z, String source) {
        long dist = Math.round(Math.sqrt(client.player.squaredDistanceTo(x, y, z)));
        MutableText msg = Text.literal("✦ Wormhole spotted! ").formatted(Formatting.RED, Formatting.BOLD)
            .append(Text.literal(String.format("at %d, %d, %d (%d blocks away) ",
                    (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), dist)).formatted(Formatting.GRAY))
            .append(Text.literal("[" + source + "]").formatted(Formatting.DARK_GRAY));
        client.player.sendMessage(msg, false);
        client.player.playSound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);
    }

    /** Called on the game thread when a packet looked like a wormhole. */
    private void onWormholePacket(MinecraftClient client, Info info) {
        if (client.player == null) return;
        long now = System.currentTimeMillis();

        if (info.pos() == null) {
            if (now - lastUnknownPosAlert < 5000) return;
            lastUnknownPosAlert = now;
            MutableText msg = Text.literal("✦ Wormhole spotted! ").formatted(Formatting.RED, Formatting.BOLD)
                .append(Text.literal("(position unknown) ").formatted(Formatting.GRAY))
                .append(Text.literal("[" + info.id() + "]").formatted(Formatting.DARK_GRAY));
            client.player.sendMessage(msg, false);
            client.player.playSound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);
            return;
        }

        double[] p = info.pos();
        wormholesSeen.removeIf(s -> now - s.time() > DEDUPE_MS);
        for (Seen s : wormholesSeen) {
            double dx = s.x() - p[0], dy = s.y() - p[1], dz = s.z() - p[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < DEDUPE_RADIUS) return; // same wormhole
        }
        wormholesSeen.add(new Seen(p[0], p[1], p[2], now));
        announceWormhole(client, p[0], p[1], p[2], info.id());
    }

    // ------------------------- Packet watcher -------------------------

    private void installPacketSpy(ClientPlayNetworkHandler handler) {
        spyInstalled = false;
        try {
            ClientConnection conn = findField(handler, ClientConnection.class);
            Channel channel = conn == null ? null : findField(conn, Channel.class);
            if (channel == null) { LOG.warn("RadiantAlert: could not find the network channel"); return; }
            if (channel.pipeline().get(SPY_NAME) != null) channel.pipeline().remove(SPY_NAME);
            channel.pipeline().addBefore("packet_handler", SPY_NAME, new PacketSpy());
            spyInstalled = true;
            LOG.info("RadiantAlert: packet watcher installed");
        } catch (Throwable t) {
            LOG.warn("RadiantAlert: could not install packet watcher", t);
        }
    }

    /** Sits in the network pipeline, looks at incoming packets, and always passes them on untouched. */
    private class PacketSpy extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                Info info = null;
                if (msg instanceof CustomPayloadS2CPacket cp) {
                    info = inspectPayload(cp.payload());
                } else if (msg instanceof ParticleS2CPacket pp) {
                    Identifier type = Registries.PARTICLE_TYPE.getId(pp.getParameters().getType());
                    info = new Info("particle:" + type, List.of(), new double[]{pp.getX(), pp.getY(), pp.getZ()}, pp.getCount());
                }
                if (info != null) {
                    boolean recording = System.currentTimeMillis() < recordUntil;
                    boolean wormhole = ALERT_WORMHOLE && matchesWormhole(info);
                    if (recording || wormhole) {
                        final Info finalInfo = info;
                        MinecraftClient.getInstance().execute(() -> handleInfo(finalInfo, wormhole));
                    }
                }
            } catch (Throwable ignored) {
                // never let our inspection interfere with the game's own packet handling
            }
            super.channelRead(ctx, msg);
        }
    }

    /** Reads the id plus every text / identifier / position value out of a custom packet. */
    private static Info inspectPayload(CustomPayload payload) {
        String id = payload.getId().id().toString();
        List<String> strings = new ArrayList<>();
        double[] pos = null;
        for (Class<?> k = payload.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(payload);
                    if (v instanceof Identifier || (v instanceof String s && s.length() <= 120)) {
                        strings.add(f.getName() + "=" + v);
                    } else if (v instanceof Vec3d vec && pos == null) {
                        pos = new double[]{vec.x, vec.y, vec.z};
                    } else if (v instanceof Vec3i vi && pos == null) {
                        pos = new double[]{vi.getX(), vi.getY(), vi.getZ()};
                    }
                } catch (Throwable ignored) {
                    // skip fields we can't read
                }
            }
        }
        return new Info(id, strings, pos, 0);
    }

    private static boolean matchesWormhole(Info info) {
        return containsAny(info.id() + " " + String.join(" ", info.strings()), WORMHOLE_PACKET_WORDS);
    }

    private void handleInfo(Info info, boolean wormhole) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (System.currentTimeMillis() < recordUntil) record(client, info);
        if (wormhole) onWormholePacket(client, info);
    }

    // ------------------------- Recording (to discover the wormhole's real name) -------------------------

    private void toggleRecord(MinecraftClient client) {
        if (client.player == null) return;
        if (System.currentTimeMillis() < recordUntil) {
            recordUntil = 0;
            client.player.sendMessage(Text.literal("[RadiantAlert] Recording stopped."), false);
            return;
        }
        if (!spyInstalled) {
            client.player.sendMessage(Text.literal("[RadiantAlert] Packet watcher is not installed (rejoin the server). Check with /radiantalert status."), false);
            return;
        }
        recordSeen.clear();
        recordShown = 0;
        recordWritten = 0;
        recordUntil = System.currentTimeMillis() + RECORD_MS;
        client.player.sendMessage(Text.literal("[RadiantAlert] Recording server packets for 2 minutes. Wait for a wormhole to open near you. "
            + "Everything is saved to radiantalert-record.txt in your Minecraft folder; the first " + RECORD_CHAT_LINES
            + " new kinds are also shown here. Run /radiantalert record again to stop."), false);
        writeRecord("---- new recording " + new Date() + " ----");
    }

    private void record(MinecraftClient client, Info info) {
        String key = info.id() + info.strings();
        if (!recordSeen.add(key)) return;
        String line = info.id() + " " + info.strings()
            + (info.pos() == null ? "" : String.format(" pos=%.0f,%.0f,%.0f", info.pos()[0], info.pos()[1], info.pos()[2]))
            + (info.count() > 0 ? " count=" + info.count() : "");
        writeRecord(line);
        if (recordShown++ < RECORD_CHAT_LINES) {
            client.player.sendMessage(Text.literal("[rec] " + line).formatted(Formatting.DARK_AQUA), false);
        }
    }

    private void writeRecord(String line) {
        if (recordWritten++ > RECORD_FILE_LINES) return;
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("radiantalert-record.txt");
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("RadiantAlert: could not write record file", e);
        }
    }

    // ------------------------- Reflection helper -------------------------

    private static <T> T findField(Object owner, Class<T> type) {
        for (Class<?> k = owner.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (type.isInstance(v)) return type.cast(v);
                } catch (Throwable ignored) {
                    // try the next field
                }
            }
        }
        return null;
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

    /** Lists every entity type within 64 blocks (except players, items and XP orbs), with counts and the nearest one's coordinates. */
    private void listEntities(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        Map<String, int[]> found = new TreeMap<>(); // id -> {count, nearest dist, x, y, z of nearest}
        for (Entity e : client.world.getEntities()) {
            int d = (int) Math.round(Math.sqrt(e.squaredDistanceTo(client.player)));
            if (d > 64) continue;
            String id = entityId(e);
            if (id.equals("minecraft:player") || id.equals("minecraft:item") || id.equals("minecraft:experience_orb")) continue;
            int[] v = found.computeIfAbsent(id, k -> new int[]{0, Integer.MAX_VALUE, 0, 0, 0});
            v[0]++;
            if (d < v[1]) { v[1] = d; v[2] = e.getBlockX(); v[3] = e.getBlockY(); v[4] = e.getBlockZ(); }
        }
        if (found.isEmpty()) {
            client.player.sendMessage(Text.literal("[RadiantAlert] No entities (other than players and items) within 64 blocks."), false);
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
