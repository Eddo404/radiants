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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Radiant Alert: tells you about radiants, shinies, Lunala/Solgaleo and Ultra Wormholes, and can
 * optionally go and deal with them for you:
 *
 *  /radiantalert auto      walk to radiants and shinies, throw balls, wait for each result,
 *                          then send "/home us" once caught
 *  /radiantalert wormhole  walk into wormholes, send out a Pokémon after the first teleport,
 *                          then send "/home us" after the second teleport
 *  /radiantalert stop      cancel everything
 *  /radiantalert listen    record a timeline (packets, chat, teleports, screens) to radiantalert-listen.txt,
 *                          handy when you switch servers and want to see what happens
 */
public class RadiantAlertClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("radiantalert");

    // ======================= Settings: change these to taste =======================

    // --- Alerts ---
    private static final boolean ALERT_RADIANT = true;
    private static final boolean ALERT_SHINY = true;
    private static final boolean ALERT_SPECIES = true;
    private static final boolean ALERT_WORMHOLE = true;

    /** Aspect names that mark a Pokémon as Radiant. Use /radiantalert debug to check what Delta uses. */
    private static final Set<String> RADIANT_ASPECTS = Set.of("radiant");

    /** Species to alert on (lowercase). Alert only: these are NOT caught automatically. */
    private static final Set<String> WATCHED_SPECIES = Set.of("lunala", "solgaleo");

    /** Shiny Pokémon of these species are ignored completely (no alert, no catching): they can't be caught. */
    private static final Set<String> IGNORED_SHINY_SPECIES = Set.of("nihilego");

    /** Wormhole as an entity: alert if the entity type id contains one of these words. */
    private static final Set<String> WORMHOLE_ENTITY_WORDS = Set.of("wormhole");

    /**
     * Wormhole as a server packet (particle effect etc.): alert if a packet's id or any of its
     * text/identifier values contains one of these words (lowercase).
     * "us_portal_rift" is Delta's wormhole effect; it comes in several colours
     * (us_portal_rift_red, ..._blue, etc.) and this matches all of them.
     */
    private static final Set<String> WORMHOLE_PACKET_WORDS = Set.of("wormhole", "us_portal_rift");

    /** One wormhole sends many packets: ignore repeats within this distance (blocks) and time (ms). */
    private static final double DEDUPE_RADIUS = 12;
    private static final long DEDUPE_MS = 30000;

    // --- Catching ---
    /** Command sent after a catch (or when a Pokémon can't be captured). No slash. */
    private static final String HOME_COMMAND = "home us";
    /** After sending the home command, don't start anything new for this long (ms), so you don't walk off mid-teleport. */
    private static final long HOME_COOLDOWN_MS = 15000;

    /** Balls for a RADIANT, best first. Must be in your hotbar. Master / Ancient Origin are allowed here. */
    private static final List<String> BALLS_RADIANT = List.of(
        "cobblemon:master_ball", "cobblemon:ancient_origin_ball",
        "cobblemon:ultra_ball", "cobblemon:great_ball", "cobblemon:poke_ball");
    /** Balls for a plain SHINY, best first. Never Master or Ancient Origin. */
    private static final List<String> BALLS_SHINY = List.of(
        "cobblemon:ultra_ball", "cobblemon:great_ball", "cobblemon:poke_ball");

    /** Throwing zone, in horizontal blocks from the Pokémon. Once inside, the wider "stay" band applies so it doesn't flip-flop. */
    private static final double ZONE_MIN_ENTER = 3.0;
    private static final double ZONE_MAX_ENTER = 5.5;
    private static final double ZONE_MIN_STAY = 2.3;
    private static final double ZONE_MAX_STAY = 6.5;
    /** The route planner aims for a spot this far (horizontal blocks) from the Pokémon, with a clear view of it. */
    private static final double GOAL_MIN = 3.3;
    private static final double GOAL_MAX = 5.0;

    /** A plain SHINY is given up on after this many balls (then it goes home). Radiants have no ball limit. */
    private static final int MAX_BALLS_SHINY = 20;
    /** Give up on any catch after this long in total (ticks, 20 = 1 second): 10 minutes. */
    private static final int MAX_CATCH_TICKS = 20 * 60 * 10;
    /** Don't chase anything further away than this (blocks). */
    private static final double MAX_APPROACH_DISTANCE = 80;

    // How the result is worked out after a throw
    private static final int BALL_APPEAR_TIMEOUT_TICKS = 40;
    private static final int FALLBACK_WAIT_TICKS = 100;
    private static final int ESCAPE_CONFIRM_TICKS = 3;
    private static final int MISS_CONFIRM_TICKS = 10;
    private static final int MAX_RESULT_TICKS = 20 * 30;
    private static final int ESCAPE_DELAY_TICKS = 20;
    private static final int FAILED_THROW_DELAY_TICKS = 40;

    // --- Route planner ---
    /** How many map squares the route planner may look at before settling for the closest it found. */
    private static final int PLAN_MAX_NODES = 15000;
    /** Minimum gap between route plans (ticks). */
    private static final int REPLAN_COOLDOWN_TICKS = 10;

    // --- Wormhole run ---
    /** Key pressed after the first teleport to send out a Pokémon (Cobblemon's default is R). */
    private static final String SEND_OUT_KEY = "key.keyboard.r";
    /** The route planner aims for a spot this close (horizontal blocks) to the wormhole's centre. */
    private static final double WORMHOLE_GOAL_RADIUS = 1.5;
    /** Counts as "at the wormhole" within this distance; from there it walks straight into the centre. */
    private static final double WORMHOLE_ARRIVE_RADIUS = 1.8;
    private static final double WORMHOLE_MAX_DISTANCE = 150;
    private static final int WORMHOLE_WALK_TIMEOUT_TICKS = 20 * 90;
    private static final int WORMHOLE_PUSH_TICKS = 100;
    /** Wait after a teleport before acting, so the new world has loaded. */
    private static final int AFTER_TELEPORT_WAIT_TICKS = 60;
    /** Give up waiting for the second teleport after this long. */
    private static final int ENCOUNTER_TIMEOUT_TICKS = 20 * 60 * 20;

    // ===============================================================================

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;
    private static final int RECORD_CHAT_LINES = 25;
    private static final int RECORD_FILE_LINES = 10000;
    private static final int AUTO_LOG_MAX_LINES = 3000;
    private static final int LISTEN_MAX_LINES = 8000;
    private static final String SPY_NAME = "radiantalert_spy";
    private static final Pattern IDENTITY_OP = Pattern.compile("\"op\"\\s*:\\s*\"identity\"");
    private static final Pattern SERVER_NAME = Pattern.compile("\"server\"\\s*:\\s*\"([^\"]+)\"");

    private enum Task { IDLE, CATCH, WORMHOLE }
    private enum Phase { READY, WAIT_BALL, WAIT_RESULT }
    private enum WStage { WALK, PUSH, ARRIVED, ENCOUNTER, FINISHED }

    private record Pending(PokemonEntity entity, int[] ticks) {}
    private record Info(String id, List<String> strings, double[] pos, int count) {}
    private record Seen(double x, double y, double z, long time) {}

    // ---- shared state ----
    private Task task = Task.IDLE;
    private boolean autoCatchEnabled = false;
    private boolean autoWormholeEnabled = false;
    private long homeCooldownUntil = 0;
    private boolean keysHeld = false;
    private int sendOutKeyTicks = 0;

    // ---- alerts ----
    private final List<Pending> pending = new ArrayList<>();
    private final Set<UUID> alerted = cappedSet(MAX_REMEMBERED);
    private final List<Seen> wormholesSeen = new ArrayList<>();
    private long lastUnknownPosAlert = 0;

    // ---- packet watcher / recorder ----
    private volatile boolean spyInstalled = false;
    private volatile boolean recording = false;
    private final Set<String> recordSeen = new HashSet<>();
    private int recordShown = 0;
    private int recordWritten = 0;

    // ---- server hop detection ----
    private ClientWorld lastWorld = null;
    private String lastServerName = null;
    private long lastHopTime = 0;

    // ---- catching ----
    private PokemonEntity target = null;
    private String targetName = "";
    private boolean allowPremiumBalls = false;
    private int catchTicks, throwsDone, throwCooldown, switchWait;
    private Phase phase = Phase.READY;
    private int phaseTicks, visibleTicks, ballGoneTicks, goneTicks;
    private Entity ball = null;
    private boolean captureStarted = false;
    private boolean zoneActive = false;
    private int zoneOverrideTicks = 0;

    // ---- wormhole run ----
    private WStage wStage = WStage.WALK;
    private Vec3d wormholePos = Vec3d.ZERO;
    private int wTicks = 0;

    // ---- route following ----
    private List<BlockPos> path = List.of();
    private int pathIndex = 0;
    private Vec3d planTargetPos = null;
    private int replanCooldown = 0, failedPlans = 0, stuckTicks = 0, stuckStrikes = 0;
    private double stuckX, stuckY, stuckZ;
    private final Set<Long> blockedNodes = new HashSet<>();

    // ---- action log ----
    private int autoLogLines = 0;

    // ---- listener (/radiantalert listen) ----
    private volatile boolean listening = false;
    private long listenStart = 0;
    private int listenLines = 0;
    private String lastScreenName = "none";

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
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> onEntityLoad(entity));

        // Watch what the server sends (particle effects, custom packets); also notices server switches
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            installPacketSpy(handler);
            client.execute(() -> onServerHop("join"));
        });

        // Chat messages (system messages and player chat)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChatMessage(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
            onChatMessage(message.getString()));

        ClientTickEvents.START_CLIENT_TICK.register(this::autoTick);
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
                .then(ClientCommandManager.literal("listen").executes(ctx -> {
                    toggleListen(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("auto").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoCatchEnabled = !autoCatchEnabled;
                    say(c, autoCatchEnabled
                        ? "Auto-catch ON: I'll walk to radiants and shinies, throw balls, and send /" + HOME_COMMAND + " once caught. Cancel with /radiantalert stop."
                        : "Auto-catch OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("wormhole").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoWormholeEnabled = !autoWormholeEnabled;
                    say(c, autoWormholeEnabled
                        ? "Auto-wormhole ON: I'll walk into wormholes that open near you. Cancel with /radiantalert stop."
                        : "Auto-wormhole OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoCatchEnabled = false;
                    autoWormholeEnabled = false;
                    abortCurrent(c, "Stopped.");
                    say(c, "Auto-catch and auto-wormhole are OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    say(c, "packet watcher: " + spyInstalled + " | recording: " + recording + " | listening: " + listening
                        + " | auto-catch: " + autoCatchEnabled + " | auto-wormhole: " + autoWormholeEnabled
                        + " | task: " + task + (task == Task.WORMHOLE ? " (" + wStage + ")" : "")
                        + " | server: " + lastServerName);
                    return 1;
                }))));
    }

    // ============================== Per-tick work ==============================

    /** End of every tick: server-hop check, Pokémon detection. */
    private void tick(MinecraftClient client) {
        // a new world object means we were moved to another server/dimension
        if (client.world != null && client.world != lastWorld) {
            lastWorld = client.world;
            onServerHop("world");
        }

        if (listening) {
            String screen = screenName(client);
            if (!screen.equals(lastScreenName)) {
                lastScreenName = screen;
                listenLog("SCREEN " + screen);
            }
        }

        if (sendOutKeyTicks > 0 && --sendOutKeyTicks == 0) {
            KeyBinding.setKeyPressed(InputUtil.fromTranslationKey(SEND_OUT_KEY), false);
        }

        if (client.player == null || client.world == null) { pending.clear(); return; }

        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            PokemonEntity e = p.entity();

            if (e.isRemoved() || ++p.ticks()[0] > MAX_WAIT_TICKS) { it.remove(); continue; }
            if (e.getOwnerUuid() != null) { it.remove(); continue; } // someone's own Pokémon

            Pokemon pokemon = e.getPokemon();
            String species = pokemon.getSpecies().getName().toLowerCase(Locale.ROOT);

            // Shiny Nihilego etc.: can't be caught, ignore completely
            if (pokemon.getShiny() && IGNORED_SHINY_SPECIES.contains(species)) {
                it.remove();
                alerted.add(e.getUuid());
                continue;
            }

            // Species is known immediately; aspects/shiny may take a few ticks to sync.
            // Keep waiting until the radiant tag shows up or time runs out, so a
            // Radiant Lunala isn't announced as just "Lunala" before the tag arrives.
            boolean radiant = ALERT_RADIANT && isRadiant(pokemon);
            boolean shiny = ALERT_SHINY && pokemon.getShiny();
            boolean watched = ALERT_SPECIES && WATCHED_SPECIES.contains(species);

            boolean waitedLongEnough = p.ticks()[0] >= 10;
            if (radiant || (waitedLongEnough && (shiny || watched))) {
                it.remove();
                if (alerted.add(e.getUuid())) {
                    announce(client, e, radiant, shiny, watched);
                    if ((radiant || shiny) && canStartTask()) {
                        if (autoCatchEnabled) startCatch(client, e, radiant);
                    }
                }
            }
        }
    }

    private boolean canStartTask() {
        return task == Task.IDLE && System.currentTimeMillis() >= homeCooldownUntil;
    }

    /** Start of every tick: drives whatever the bot is currently doing. */
    private void autoTick(MinecraftClient client) {
        if (task == Task.IDLE) { releaseKeys(client); return; }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { releaseKeys(client); return; }
        if (client.currentScreen != null) { releaseKeys(client); return; } // paused while chat/inventory/battle is open

        if (task == Task.CATCH) catchTick(client, player);
        else wormholeTick(client, player);
    }

    private void abortCurrent(MinecraftClient client, String message) {
        switch (task) {
            case CATCH -> stopAuto(client, message);
            case WORMHOLE -> stopWormhole(client, message);
            default -> { }
        }
    }

    // ============================== Alerts ==============================

    private static boolean isRadiant(Pokemon pokemon) {
        for (String aspect : pokemon.getAspects()) {
            if (RADIANT_ASPECTS.contains(aspect.toLowerCase(Locale.ROOT))) return true;
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

    private static boolean isBallId(String id) {
        return id.startsWith("cobblemon:") && id.contains("ball");
    }

    private void onEntityLoad(Entity entity) {
        if (listening) logEntityLoad(entity);
        if (entity instanceof PokemonEntity pe) {
            if (!alerted.contains(pe.getUuid())) pending.add(new Pending(pe, new int[]{0}));
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String id = entityId(entity);

        // The ball we just threw has appeared in the world
        if (task == Task.CATCH && phase == Phase.WAIT_BALL && ball == null && isBallId(id)
                && entity.squaredDistanceTo(client.player) < 100) {
            ball = entity;
            phase = Phase.WAIT_RESULT;
            phaseTicks = 0;
            say(client, "Ball #" + throwsDone + " is in the air.");
        }

        if (ALERT_WORMHOLE && containsAny(id, WORMHOLE_ENTITY_WORDS) && alerted.add(entity.getUuid())) {
            announceWormhole(client, entity.getX(), entity.getY(), entity.getZ(), id);
        }
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
                .append(Text.literal("[" + describe(info) + "]").formatted(Formatting.DARK_GRAY));
            client.player.sendMessage(msg, false);
            client.player.playSound(SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);
            return;
        }

        double[] p = info.pos();
        wormholesSeen.removeIf(s -> now - s.time() > DEDUPE_MS);
        for (int i = 0; i < wormholesSeen.size(); i++) {
            Seen s = wormholesSeen.get(i);
            double dx = s.x() - p[0], dy = s.y() - p[1], dz = s.z() - p[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < DEDUPE_RADIUS) {
                // same wormhole still sending effects: refresh its timer, don't alert again
                wormholesSeen.set(i, new Seen(s.x(), s.y(), s.z(), now));
                return;
            }
        }
        wormholesSeen.add(new Seen(p[0], p[1], p[2], now));
        announceWormhole(client, p[0], p[1], p[2], describe(info));

        if (autoWormholeEnabled && canStartTask()) startWormhole(client, new Vec3d(p[0], p[1], p[2]));
    }

    /** Short label for the alert: the matching effect name, e.g. "us_portal_rift_red". */
    private static String describe(Info info) {
        for (String text : info.strings()) {
            if (containsAny(text, WORMHOLE_PACKET_WORDS)) {
                int eq = text.indexOf('=');
                String v = eq >= 0 ? text.substring(eq + 1) : text;
                int colon = v.indexOf(':');
                return colon >= 0 ? v.substring(colon + 1) : v;
            }
        }
        return info.id();
    }

    // ============================== Chat and server hops ==============================

    private void onChatMessage(String text) {
        listenLog("CHAT " + text);
        String lower = text.toLowerCase(Locale.ROOT);
        if (task == Task.CATCH && lower.contains("cannot be captured")) {
            MinecraftClient client = MinecraftClient.getInstance();
            autoLog("CHAT uncapturable: " + text);
            stopAuto(client, targetName + " can't be captured - aborting.");
            sendHome(client);
        }
    }

    /** We were moved to a different server or dimension. */
    private void onServerHop(String reason) {
        listenLog("SIGNAL " + reason + " | " + contextString(MinecraftClient.getInstance()));
        long now = System.currentTimeMillis();
        if (now - lastHopTime < 3000) return; // several signals fire for one hop
        lastHopTime = now;
        autoLog("HOP (" + reason + ") task=" + task + (task == Task.WORMHOLE ? " stage=" + wStage : ""));

        MinecraftClient client = MinecraftClient.getInstance();
        if (task == Task.CATCH) {
            stopAuto(client, "The server changed - stopping the catch.");
        } else if (task == Task.WORMHOLE) {
            switch (wStage) {
                case WALK, PUSH -> {
                    wStage = WStage.ARRIVED;
                    wTicks = 0;
                    releaseKeys(client);
                    say(client, "Teleported! Waiting a moment, then sending out a Pokémon...");
                }
                case ENCOUNTER -> {
                    wStage = WStage.FINISHED;
                    wTicks = 0;
                    say(client, "Teleported again - sending /" + HOME_COMMAND + " shortly.");
                }
                default -> { }
            }
        }
    }

    private void onIdentityInfo(Info info) {
        boolean identity = false;
        String server = null;
        for (String s : info.strings()) {
            if (!s.startsWith("json=")) continue;
            if (IDENTITY_OP.matcher(s).find()) identity = true;
            Matcher m = SERVER_NAME.matcher(s);
            if (m.find()) server = m.group(1);
        }
        if (!identity || server == null) return;
        boolean changed = lastServerName != null && !lastServerName.equals(server);
        lastServerName = server;
        if (changed) onServerHop("identity:" + server);
    }

    private void sendHome(MinecraftClient client) {
        if (client.player == null) return;
        homeCooldownUntil = System.currentTimeMillis() + HOME_COOLDOWN_MS;
        client.player.networkHandler.sendChatCommand(HOME_COMMAND);
        autoLog("SENT /" + HOME_COMMAND);
        say(client, "Sent /" + HOME_COMMAND);
    }

    // ============================== Catching ==============================

    private void startCatch(MinecraftClient client, PokemonEntity e, boolean radiant) {
        if (client.player.distanceTo(e) > MAX_APPROACH_DISTANCE) {
            say(client, "Too far away to chase (more than " + (int) MAX_APPROACH_DISTANCE + " blocks).");
            return;
        }
        task = Task.CATCH;
        target = e;
        targetName = e.getPokemon().getSpecies().getName();
        allowPremiumBalls = radiant;
        catchTicks = 0;
        throwsDone = 0;
        throwCooldown = 0;
        switchWait = 0;
        phase = Phase.READY;
        ball = null;
        zoneActive = false;
        zoneOverrideTicks = 0;
        resetRoute(client);
        autoLog("CATCH start " + targetName + " radiant=" + radiant);
        say(client, "Heading to " + (radiant ? "Radiant " : "Shiny ") + targetName
            + (radiant ? " (Master/Ancient Origin balls allowed)." : " (no Master/Ancient Origin balls).")
            + " Open chat and run /radiantalert stop to cancel.");
    }

    private void catchTick(MinecraftClient client, ClientPlayerEntity player) {
        if (++catchTicks > MAX_CATCH_TICKS) { stopAuto(client, "Timed out."); return; }

        // The same Pokémon entity stays in the world the whole time (it just turns invisible
        // inside the ball), so we follow that one entity. Removed = gone.
        PokemonEntity current = target == null || target.isRemoved() ? null : target;

        // A ball is out: hold still and wait to see what happens to it
        if (phase != Phase.READY) {
            releaseKeys(client);
            resolveTick(client, player, current);
            return;
        }

        if (current == null) {
            stopAuto(client, throwsDone > 0
                ? targetName + " is no longer here after " + throwsDone + " ball(s) (caught, fled or despawned)."
                : targetName + " disappeared before I could throw.");
            return;
        }

        if (player.distanceTo(current) > MAX_APPROACH_DISTANCE) { stopAuto(client, "Too far away - stopping."); return; }

        if (throwCooldown > 0) throwCooldown--;
        if (zoneOverrideTicks > 0) zoneOverrideTicks--;

        if (inThrowZone(player, current) || zoneOverrideTicks > 0) {
            // close enough, not too close, and in view: stand still and throw
            releaseKeys(client);
            path = List.of();
            aimAt(player, current);
            throwTick(client, player, current);
        } else {
            followPath(client, player, current.getPos(), current.getEyeY(), true);
        }
    }

    /** Horizontal distance in the throwing band, roughly level with the Pokémon, and in view. */
    private boolean inThrowZone(ClientPlayerEntity player, PokemonEntity t) {
        double dx = t.getX() - player.getX();
        double dz = t.getZ() - player.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        double lo = zoneActive ? ZONE_MIN_STAY : ZONE_MIN_ENTER;
        double hi = zoneActive ? ZONE_MAX_STAY : ZONE_MAX_ENTER;
        zoneActive = hd >= lo && hd <= hi && Math.abs(t.getY() - player.getY()) <= 3.5 && player.canSee(t);
        return zoneActive;
    }

    private void throwTick(MinecraftClient client, ClientPlayerEntity player, PokemonEntity current) {
        if (throwCooldown > 0) return;
        if (!allowPremiumBalls && throwsDone >= MAX_BALLS_SHINY) {
            // a plain shiny only gets MAX_BALLS_SHINY balls; then give up and go home
            stopAuto(client, "Used " + MAX_BALLS_SHINY + " balls on " + targetName + " - giving up.");
            sendHome(client);
            return;
        }
        if (switchWait > 0) { switchWait--; return; }
        if (angleError(player, current) > 4.0f) return; // wait until we're aimed at it

        int slot = findBallIndex(player);
        if (slot < 0) {
            stopAuto(client, allowPremiumBalls
                ? "No usable ball anywhere in your inventory."
                : "No usable ball anywhere in your inventory (Master/Ancient Origin balls are not used for shinies).");
            return;
        }
        if (client.interactionManager == null) return;
        if (slot >= 9) {
            // the ball is in the main inventory: swap it into the selected hotbar slot
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                player.getInventory().selectedSlot, SlotActionType.SWAP, player);
            autoLog("Moved a ball from inventory slot " + slot + " to the hotbar");
            switchWait = 5; // give the inventory change a moment to register
            return;
        }
        if (player.getInventory().selectedSlot != slot) {
            player.getInventory().selectedSlot = slot;
            switchWait = 4; // give the game a moment to register the new slot
            return;
        }
        if (client.interactionManager == null) return;

        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        throwsDone++;

        // now wait for the result instead of throwing again on a timer
        phase = Phase.WAIT_BALL;
        phaseTicks = 0;
        visibleTicks = 0;
        ballGoneTicks = 0;
        goneTicks = 0;
        ball = null;
        captureStarted = false;
        autoLog("THROW #" + throwsDone);
        say(client, "Threw ball #" + throwsDone + " at " + targetName + ". Waiting for the result...");
    }

    /** Inventory index (0-8 hotbar, 9-35 main inventory) of the best allowed ball, or -1. The hotbar is checked first. */
    private int findBallIndex(ClientPlayerEntity player) {
        List<String> order = allowPremiumBalls ? BALLS_RADIANT : BALLS_SHINY;
        for (String wanted : order) {
            for (int i = 0; i < 36; i++) {
                ItemStack s = player.getInventory().getStack(i);
                if (!s.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(wanted)) return i;
            }
        }
        // fallback: any other Cobblemon ball (premium balls only if allowed)
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).toString();
            if (!id.startsWith("cobblemon:") || !id.endsWith("_ball")) continue;
            if (!allowPremiumBalls && (id.endsWith("master_ball") || id.endsWith("ancient_origin_ball"))) continue;
            return i;
        }
        return -1;
    }

    /** Runs every tick while a ball is out. Decides: still going, caught, broke free, or missed. */
    private void resolveTick(MinecraftClient client, ClientPlayerEntity player, PokemonEntity current) {
        phaseTicks++;
        boolean gone = current == null;
        boolean hidden = !gone && current.isInvisible();
        if (!gone && !hidden) aimAt(player, current);

        if (phase == Phase.WAIT_BALL) {
            // we threw, but haven't seen a ball entity yet
            if (phaseTicks > BALL_APPEAR_TIMEOUT_TICKS) {
                say(client, "Couldn't see the ball - waiting " + (FALLBACK_WAIT_TICKS / 20) + "s before the next throw instead.");
                autoLog("RESULT no ball entity seen, using fallback wait");
                phase = Phase.READY;
                throwCooldown = FALLBACK_WAIT_TICKS;
            }
            return;
        }

        // phase == WAIT_RESULT
        boolean ballGone = ball == null || ball.isRemoved();

        // The Pokémon turns invisible while it is inside the ball
        if (hidden && !captureStarted) {
            captureStarted = true;
            autoLog("RESULT " + targetName + " went into the ball");
            say(client, targetName + " went into the ball...");
        }

        // Caught: the Pokémon entity is removed after it went into the ball
        if (gone) {
            goneTicks++;
            if (captureStarted || (ballGone && goneTicks >= 20)) { onCaught(client); return; }
        } else {
            goneTicks = 0;
        }

        // Broke free: visible again and the ball has disappeared
        if (captureStarted && !gone && !hidden) {
            visibleTicks++;
            if (ballGone && visibleTicks >= ESCAPE_CONFIRM_TICKS) { onEscaped(client, true); return; }
        } else {
            visibleTicks = 0;
        }

        // Missed: the ball is gone and the Pokémon never went into it
        if (ballGone) ballGoneTicks++; else ballGoneTicks = 0;
        if (!captureStarted && !gone && ballGone && ballGoneTicks >= MISS_CONFIRM_TICKS) { onEscaped(client, false); return; }

        if (phaseTicks > MAX_RESULT_TICKS) {
            if (gone) onCaught(client);
            else if (!hidden) onEscaped(client, captureStarted);
            else stopAuto(client, "Couldn't tell what happened to the ball - stopping.");
        }
    }

    /** It broke free, or the ball missed. It simply tries again (there is no ball limit). */
    private void onEscaped(MinecraftClient client, boolean brokeFree) {
        autoLog("RESULT " + (brokeFree ? "broke free" : "missed"));
        say(client, targetName + (brokeFree ? " broke free!" : " wasn't captured (the ball missed or was discarded).")
            + " Balls used so far: " + throwsDone + ".");
        phase = Phase.READY;
        throwCooldown = brokeFree ? ESCAPE_DELAY_TICKS : FAILED_THROW_DELAY_TICKS;
        ball = null;
        zoneActive = false; // it will probably be right next to us now: re-check the distance from scratch
    }

    /** It was caught: tell the player and go home. */
    private void onCaught(MinecraftClient client) {
        autoLog("RESULT caught");
        String name = targetName;
        int balls = throwsDone;
        stopAuto(client, "✔ Caught " + name + " with ball #" + balls + "!");
        if (client.player != null) client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        sendHome(client);
    }

    private void stopAuto(MinecraftClient client, String message) {
        task = Task.IDLE;
        target = null;
        phase = Phase.READY;
        ball = null;
        path = List.of();
        zoneActive = false;
        releaseKeys(client);
        autoLog("CATCH stop: " + message);
        say(client, message);
    }

    // ============================== Wormhole run ==============================

    private void startWormhole(MinecraftClient client, Vec3d pos) {
        if (Math.sqrt(client.player.squaredDistanceTo(pos.x, pos.y, pos.z)) > WORMHOLE_MAX_DISTANCE) {
            say(client, "That wormhole is too far away to walk to.");
            return;
        }
        task = Task.WORMHOLE;
        wStage = WStage.WALK;
        wormholePos = pos;
        wTicks = 0;
        resetRoute(client);
        autoLog(String.format("WORMHOLE start at %.1f, %.1f, %.1f", pos.x, pos.y, pos.z));
        say(client, "Walking to the wormhole. Open chat and run /radiantalert stop to cancel.");
    }

    private void wormholeTick(MinecraftClient client, ClientPlayerEntity player) {
        wTicks++;

        switch (wStage) {
            case WALK -> {
                if (wTicks > WORMHOLE_WALK_TIMEOUT_TICKS) { stopWormhole(client, "Couldn't reach the wormhole in time."); return; }
                double hd = Math.hypot(player.getX() - wormholePos.x, player.getZ() - wormholePos.z);
                if (hd <= WORMHOLE_ARRIVE_RADIUS && Math.abs(player.getY() - wormholePos.y) <= 4.5) {
                    wStage = WStage.PUSH;
                    wTicks = 0;
                    path = List.of();
                    autoLog("WORMHOLE arrived, pushing in");
                    return;
                }
                followPath(client, player, wormholePos, wormholePos.y, false);
            }
            case PUSH -> {
                if (wTicks > WORMHOLE_PUSH_TICKS) {
                    stopWormhole(client, "Reached the wormhole but wasn't teleported.");
                    return;
                }
                // walk straight into the centre
                float yaw = (float) Math.toDegrees(Math.atan2(wormholePos.z - player.getZ(), wormholePos.x - player.getX())) - 90.0f;
                player.setYaw(MathHelper.lerpAngleDegrees(0.5f, player.getYaw(), yaw));
                client.options.forwardKey.setPressed(true);
                client.options.sprintKey.setPressed(false);
                client.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
                keysHeld = true;
            }
            case ARRIVED -> {
                releaseKeys(client);
                if (wTicks >= AFTER_TELEPORT_WAIT_TICKS) {
                    pressSendOutKey();
                    wStage = WStage.ENCOUNTER;
                    wTicks = 0;
                    autoLog("WORMHOLE sent out Pokémon (key)");
                    say(client, "Sent out a Pokémon. Waiting for the encounter to finish...");
                }
            }
            case ENCOUNTER -> {
                releaseKeys(client);
                if (wTicks > ENCOUNTER_TIMEOUT_TICKS) stopWormhole(client, "Timed out waiting for the encounter to finish.");
            }
            case FINISHED -> {
                releaseKeys(client);
                if (wTicks >= AFTER_TELEPORT_WAIT_TICKS) {
                    stopWormhole(client, "Wormhole run finished.");
                    sendHome(client);
                }
            }
        }
    }

    private void stopWormhole(MinecraftClient client, String message) {
        task = Task.IDLE;
        path = List.of();
        releaseKeys(client);
        autoLog("WORMHOLE stop: " + message);
        say(client, message);
    }

    /** Presses the Pokémon send-out key (R by default) as if you had typed it. */
    private void pressSendOutKey() {
        InputUtil.Key key = InputUtil.fromTranslationKey(SEND_OUT_KEY);
        KeyBinding.setKeyPressed(key, true);
        KeyBinding.onKeyPressed(key);
        sendOutKeyTicks = 3;
    }

    // ============================== Route following ==============================

    private void resetRoute(MinecraftClient client) {
        path = List.of();
        pathIndex = 0;
        planTargetPos = null;
        replanCooldown = 0;
        failedPlans = 0;
        stuckTicks = 0;
        stuckStrikes = 0;
        stuckX = client.player.getX();
        stuckY = client.player.getY();
        stuckZ = client.player.getZ();
        blockedNodes.clear();
    }

    private void releaseKeys(MinecraftClient client) {
        if (!keysHeld) return;
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        keysHeld = false;
    }

    /**
     * Walks along a planned route to the goal. ring = true: a spot a few blocks from the goal with a
     * clear view of it (for throwing). ring = false: right up to the goal point (for wormholes).
     */
    private void followPath(MinecraftClient client, ClientPlayerEntity player, Vec3d goal, double goalEyeY, boolean ring) {
        if (replanCooldown > 0) replanCooldown--;

        boolean noPath = path.isEmpty() || pathIndex >= path.size();
        boolean goalMoved = planTargetPos == null || goal.squaredDistanceTo(planTargetPos) > 9.0;
        boolean offPath = !noPath && isOffPath(player, path.get(pathIndex));

        if (noPath || goalMoved || offPath) {
            if (replanCooldown > 0) { releaseKeys(client); return; }
            replanCooldown = REPLAN_COOLDOWN_TICKS;
            if (!replan(client, player, goal, goalEyeY, ring)) return; // gave up
            stuckTicks = 0;
            stuckX = player.getX();
            stuckY = player.getY();
            stuckZ = player.getZ();
        }
        if (path.isEmpty() || pathIndex >= path.size()) { releaseKeys(client); return; }

        // move on to the next waypoint once we're standing on the current one
        BlockPos wp = path.get(pathIndex);
        while (horizontalDistance(player, wp) < 0.5 && Math.abs(wp.getY() - player.getY()) < 1.5) {
            pathIndex++;
            if (pathIndex >= path.size()) { path = List.of(); releaseKeys(client); return; }
            wp = path.get(pathIndex);
        }

        // not making progress for a second: block that square and plan a different route
        if (++stuckTicks >= 20) {
            double moved = Math.abs(player.getX() - stuckX) + Math.abs(player.getY() - stuckY) + Math.abs(player.getZ() - stuckZ);
            stuckTicks = 0;
            stuckX = player.getX();
            stuckY = player.getY();
            stuckZ = player.getZ();
            if (moved < 0.4) {
                stuckStrikes++;
                if (stuckStrikes >= 6) { abortCurrent(client, "Got stuck and couldn't find a way out - stopping."); return; }
                blockedNodes.add(wp.asLong());
                path = List.of();
                replanCooldown = 0;
                releaseKeys(client);
                say(client, "Stuck - trying a different route.");
                return;
            } else if (stuckStrikes > 0) {
                stuckStrikes--;
            }
        }

        // steer toward the waypoint
        double dx = wp.getX() + 0.5 - player.getX();
        double dz = wp.getZ() + 0.5 - player.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        double dy = wp.getY() - player.getY();

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        player.setYaw(MathHelper.lerpAngleDegrees(0.5f, player.getYaw(), yaw));
        player.setPitch(MathHelper.lerp(0.5f, player.getPitch(), 0.0f));
        float yawError = Math.abs(MathHelper.wrapDegrees(yaw - player.getYaw()));

        boolean forward = yawError < 60.0f;
        boolean jump = player.isTouchingWater()
            || (player.isOnGround() && ((dy > 0.3 && hd < 1.6) || player.horizontalCollision));
        boolean sprint = hd > 2.5 && dy < 0.3 && yawError < 15.0f;

        client.options.forwardKey.setPressed(forward);
        client.options.sprintKey.setPressed(sprint && forward);
        client.options.jumpKey.setPressed(jump);
        keysHeld = true;
    }

    private static double horizontalDistance(ClientPlayerEntity player, BlockPos p) {
        double dx = p.getX() + 0.5 - player.getX();
        double dz = p.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean isOffPath(ClientPlayerEntity player, BlockPos wp) {
        return horizontalDistance(player, wp) > 5.0 || Math.abs(wp.getY() - player.getY()) > 3.5;
    }

    /** Plans a new route. Returns false if the current task was given up. */
    private boolean replan(MinecraftClient client, ClientPlayerEntity player, Vec3d goal, double goalEyeY, boolean ring) {
        planTargetPos = goal;
        Plan plan = new Pathfinder(client.world, player, goal, goalEyeY, ring, blockedNodes).search();
        autoLog("PLAN " + plan.status() + " steps=" + plan.nodes().size());

        switch (plan.status()) {
            case AT_GOAL -> {
                // already standing in a good spot
                path = List.of();
                pathIndex = 0;
                zoneOverrideTicks = ring ? 40 : 0;
                failedPlans = 0;
            }
            case PATH, PARTIAL -> {
                path = plan.nodes();
                pathIndex = 0;
                failedPlans = 0;
            }
            default -> {
                path = List.of();
                pathIndex = 0;
                if (++failedPlans >= 3) {
                    abortCurrent(client, "Couldn't find a way there - stopping.");
                    return false;
                }
            }
        }
        return true;
    }

    // ============================== Route planner (A*) ==============================

    private enum Status { PATH, PARTIAL, AT_GOAL, FAIL }

    private record Plan(Status status, List<BlockPos> nodes) {}

    private record Move(int x, int y, int z, double cost) {}

    private static final class Node {
        final BlockPos pos;
        Node parent;
        double g;
        double f;
        boolean closed;

        Node(BlockPos pos, Node parent, double g) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
        }
    }

    private record Entry(Node node, double f) {}

    /**
     * Searches the loaded blocks for a way to the goal. Moves: walk, walk diagonally, step up one
     * block (jump), drop down up to three. Lava, fire, cactus, magma, berry bushes, cobwebs and
     * powder snow are no-go, and deep water doesn't count as ground. If the goal is too far for
     * one search, it returns a route to the closest point it found, and the next plan carries on
     * from there.
     */
    private static final class Pathfinder {
        private final ClientWorld world;
        private final ClientPlayerEntity player;
        private final double tx, ty, tz, goalEyeY;
        private final boolean ring;
        private final Set<Long> blocked;
        private final Map<Long, Boolean> standCache = new HashMap<>();
        private final Map<Long, Boolean> goalCache = new HashMap<>();

        Pathfinder(ClientWorld world, ClientPlayerEntity player, Vec3d goal, double goalEyeY, boolean ring, Set<Long> blocked) {
            this.world = world;
            this.player = player;
            this.tx = goal.x;
            this.ty = goal.y;
            this.tz = goal.z;
            this.goalEyeY = goalEyeY;
            this.ring = ring;
            this.blocked = blocked;
        }

        Plan search() {
            BlockPos start = BlockPos.ofFloored(player.getX(), player.getY() + 0.05, player.getZ());
            if (isGoal(start)) return new Plan(Status.AT_GOAL, List.of());

            PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
            Map<Long, Node> nodes = new HashMap<>();
            Node first = new Node(start, null, 0);
            first.f = heuristic(start);
            nodes.put(start.asLong(), first);
            open.add(new Entry(first, first.f));

            double startH = heuristic(start);
            Node best = first;
            double bestH = startH;
            int expanded = 0;
            List<Move> moves = new ArrayList<>();

            while (!open.isEmpty() && expanded < PLAN_MAX_NODES) {
                Entry entry = open.poll();
                Node cur = entry.node();
                if (cur.closed || entry.f() > cur.f + 1e-9) continue; // outdated queue entry
                cur.closed = true;
                expanded++;

                if (cur != first && isGoal(cur.pos)) return new Plan(Status.PATH, build(cur));

                double h = heuristic(cur.pos);
                if (h < bestH) { bestH = h; best = cur; }

                moves.clear();
                generateMoves(cur.pos, moves);
                for (Move m : moves) {
                    BlockPos np = new BlockPos(m.x(), m.y(), m.z());
                    long key = np.asLong();
                    double ng = cur.g + m.cost();
                    Node nb = nodes.get(key);
                    if (nb == null) {
                        nb = new Node(np, cur, ng);
                        nb.f = ng + heuristic(np);
                        nodes.put(key, nb);
                        open.add(new Entry(nb, nb.f));
                    } else if (!nb.closed && ng < nb.g) {
                        nb.g = ng;
                        nb.parent = cur;
                        nb.f = ng + heuristic(np);
                        open.add(new Entry(nb, nb.f));
                    }
                }
            }

            // no complete route within the search limit: head for the closest point found
            if (best != first && bestH < startH - 1.0) return new Plan(Status.PARTIAL, build(best));
            return new Plan(Status.FAIL, List.of());
        }

        private static List<BlockPos> build(Node end) {
            List<BlockPos> out = new ArrayList<>();
            for (Node n = end; n != null && n.parent != null; n = n.parent) out.add(n.pos);
            Collections.reverse(out);
            return out;
        }

        private double heuristic(BlockPos p) {
            double reach = ring ? GOAL_MAX : WORMHOLE_GOAL_RADIUS;
            return Math.max(0.0, Math.hypot(p.getX() + 0.5 - tx, p.getZ() + 0.5 - tz) - reach);
        }

        private boolean isGoal(BlockPos p) {
            long key = p.asLong();
            Boolean cached = goalCache.get(key);
            if (cached != null) return cached;

            double hd = Math.hypot(p.getX() + 0.5 - tx, p.getZ() + 0.5 - tz);
            boolean ok = false;
            if (ring) {
                // a spot a few blocks from the Pokémon, roughly level with it, with nothing in the way
                if (hd >= GOAL_MIN && hd <= GOAL_MAX && Math.abs(p.getY() - ty) <= 3.0) {
                    Vec3d from = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                    Vec3d to = new Vec3d(tx, goalEyeY, tz);
                    ok = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, player)).getType() == HitResult.Type.MISS;
                }
            } else {
                // right next to the point itself
                ok = hd <= WORMHOLE_GOAL_RADIUS && Math.abs(p.getY() - ty) <= 4.0;
            }
            goalCache.put(key, ok);
            return ok;
        }

        private void generateMoves(BlockPos p, List<Move> out) {
            int x = p.getX(), y = p.getY(), z = p.getZ();

            int[][] cardinals = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : cardinals) {
                int nx = x + d[0], nz = z + d[1];
                if (standable(nx, y, nz)) {
                    out.add(new Move(nx, y, nz, 1.0));                       // walk
                } else if (!passable(nx, y, nz)) {
                    // blocked at foot level: maybe a one-block step up (needs room to jump)
                    if (passable(x, y + 2, z) && standable(nx, y + 1, nz)) {
                        out.add(new Move(nx, y + 1, nz, 1.6));
                    }
                } else if (passable(nx, y + 1, nz)) {
                    // open, but nothing to stand on: maybe a drop of up to three blocks
                    for (int drop = 1; drop <= 3; drop++) {
                        int ny = y - drop;
                        if (standable(nx, ny, nz)) { out.add(new Move(nx, ny, nz, 1.0 + 0.4 * drop)); break; }
                        if (!passable(nx, ny, nz)) break;
                    }
                }
            }

            int[][] diagonals = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            for (int[] d : diagonals) {
                int nx = x + d[0], nz = z + d[1];
                if (standable(nx, y, nz)
                        && passable(nx, y, z) && passable(nx, y + 1, z)
                        && passable(x, y, nz) && passable(x, y + 1, nz)) {
                    out.add(new Move(nx, y, nz, 1.45));                      // walk diagonally (corners must be clear)
                }
            }
        }

        /** Can stand here: feet and head free, solid ground below, not a square we found to be a dead end. */
        private boolean standable(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            Boolean cached = standCache.get(key);
            if (cached != null) return cached;
            boolean ok = y > world.getBottomY() + 1 && y < world.getTopY() - 3
                && !blocked.contains(key)
                && passable(x, y, z) && passable(x, y + 1, z) && hasGround(x, y - 1, z);
            standCache.put(key, ok);
            return ok;
        }

        /** Free to be in: nothing solid (carpets and the like are fine) and nothing harmful. */
        private boolean passable(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (dangerous(state)) return false;
            VoxelShape shape = state.getCollisionShape(world, pos);
            return shape.isEmpty() || shape.getMax(Direction.Axis.Y) <= 0.1;
        }

        /** Solid to stand on, at most a full block high (so not a fence or wall). */
        private boolean hasGround(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (dangerous(state)) return false;
            VoxelShape shape = state.getCollisionShape(world, pos);
            return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) <= 1.0001;
        }

        private static boolean dangerous(BlockState state) {
            return state.getFluidState().isIn(FluidTags.LAVA)
                || state.isIn(BlockTags.FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.COBWEB)
                || state.isOf(Blocks.POWDER_SNOW);
        }
    }

    // ============================== Aiming ==============================

    private static float[] desiredAngles(ClientPlayerEntity player, Entity t) {
        Vec3d eye = player.getEyePos();
        Vec3d to = new Vec3d(t.getX(), t.getY() + t.getHeight() * 0.5, t.getZ()).subtract(eye);
        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));
        return new float[]{yaw, pitch};
    }

    private static void aimAt(ClientPlayerEntity player, Entity t) {
        float[] a = desiredAngles(player, t);
        player.setYaw(MathHelper.lerpAngleDegrees(0.4f, player.getYaw(), a[0]));
        player.setPitch(MathHelper.lerp(0.4f, player.getPitch(), a[1]));
    }

    private static float angleError(ClientPlayerEntity player, Entity t) {
        float[] a = desiredAngles(player, t);
        return Math.max(Math.abs(MathHelper.wrapDegrees(a[0] - player.getYaw())), Math.abs(a[1] - player.getPitch()));
    }

    // ============================== Packet watcher ==============================

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
                    boolean rec = recording;
                    boolean wormhole = ALERT_WORMHOLE && matchesWormhole(info);
                    boolean identity = info.id().equals("deltaclient:ultra_portal_state");
                    boolean listen = listening && !info.id().startsWith("particle:");
                    if (rec || wormhole || identity || listen) {
                        final Info finalInfo = info;
                        MinecraftClient.getInstance().execute(() -> handleInfo(finalInfo, wormhole, identity));
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
                    if (v instanceof Identifier || (v instanceof String s && s.length() <= 600)) {
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

    private void handleInfo(Info info, boolean wormhole, boolean identity) {
        if (identity) onIdentityInfo(info);
        if (listening && !info.id().startsWith("particle:")) {
            listenLog("PACKET " + info.id() + " " + info.strings()
                + (info.pos() == null ? "" : String.format(" pos=%.0f,%.0f,%.0f", info.pos()[0], info.pos()[1], info.pos()[2])));
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (recording) record(client, info);
        if (wormhole) onWormholePacket(client, info);
    }

    // ============================== Recording ==============================

    private void toggleRecord(MinecraftClient client) {
        if (client.player == null) return;
        if (recording) {
            recording = false;
            client.player.sendMessage(Text.literal("[RadiantAlert] Recording stopped. Results are in radiantalert-record.txt in your Minecraft folder."), false);
            return;
        }
        if (!spyInstalled) {
            client.player.sendMessage(Text.literal("[RadiantAlert] Packet watcher is not installed (rejoin the server). Check with /radiantalert status."), false);
            return;
        }
        recordSeen.clear();
        recordShown = 0;
        recordWritten = 0;
        recording = true;
        client.player.sendMessage(Text.literal("[RadiantAlert] Recording server packets until you stop it. "
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
        appendToFile("radiantalert-record.txt", line);
    }

    // ============================== Listener (/radiantalert listen) ==============================

    /**
     * Records a timeline to radiantalert-listen.txt: every teleport signal (join, world change,
     * Delta's server-name message), every custom packet, chat message, screen that opens, nearby
     * entity that appears, and everything this mod does. It keeps running when you switch servers.
     */
    private void toggleListen(MinecraftClient client) {
        if (client.player == null) return;
        if (listening) {
            listenLog("STOP");
            listening = false;
            say(client, "Listener stopped. Results are in radiantalert-listen.txt in your Minecraft folder.");
            return;
        }
        listenStart = System.currentTimeMillis();
        listenLines = 0;
        lastScreenName = screenName(client);
        appendToFile("radiantalert-listen.txt", "---- new listen session " + new Date() + " ----");
        listening = true;
        listenLog("START " + contextString(client));
        say(client, "Listening. Switch servers and do whatever you want recorded, then run /radiantalert listen again to stop. "
            + "Everything is saved to radiantalert-listen.txt in your Minecraft folder.");
    }

    private void listenLog(String text) {
        if (!listening) return;
        if (listenLines++ > LISTEN_MAX_LINES) return;
        double secs = (System.currentTimeMillis() - listenStart) / 1000.0;
        appendToFile("radiantalert-listen.txt", String.format("[+%.2fs] %s", secs, text));
    }

    private static String screenName(MinecraftClient client) {
        if (client.currentScreen == null) return "none";
        return client.currentScreen.getClass().getSimpleName() + " \"" + client.currentScreen.getTitle().getString() + "\"";
    }

    private String contextString(MinecraftClient client) {
        String dim = client.world == null ? "none" : client.world.getRegistryKey().getValue().toString();
        String pos = client.player == null ? "?"
            : String.format("%.0f,%.0f,%.0f", client.player.getX(), client.player.getY(), client.player.getZ());
        return "dim=" + dim + " server=" + lastServerName + " pos=" + pos + " screen=" + screenName(client);
    }

    private void logEntityLoad(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || entity == client.player) return;
        String id = entityId(entity);
        if (id.equals("minecraft:item") || id.equals("minecraft:experience_orb")) return;
        if (entity.squaredDistanceTo(client.player) > 40 * 40) return;
        String extra = entity instanceof PokemonEntity pe ? " (" + pe.getPokemon().getSpecies().getName() + ")" : "";
        listenLog(String.format("LOAD %s%s at %.1f, %.1f, %.1f", id, extra, entity.getX(), entity.getY(), entity.getZ()));
    }

    // ============================== Logging and helpers ==============================

    /** Short action log (catches, plans, hops, commands) saved to radiantalert-auto.txt. */
    private void autoLog(String text) {
        listenLog("[bot] " + text);
        if (autoLogLines++ > AUTO_LOG_MAX_LINES) return;
        String time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        appendToFile("radiantalert-auto.txt", "[" + time + "] " + text);
    }

    private static void appendToFile(String name, String line) {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve(name);
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("RadiantAlert: could not write " + name, e);
        }
    }

    private static void say(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[RadiantAlert] " + text).formatted(Formatting.GRAY), false);
        }
    }

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

    // ============================== Debug commands ==============================

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
            say(client, "No Pokémon within 48 blocks.");
            return;
        }
        Pokemon p = nearest.getPokemon();
        say(client, p.getSpecies().getName() + " | shiny=" + p.getShiny() + " | aspects=" + p.getAspects()
            + " | radiant=" + isRadiant(p));
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
            say(client, "No entities (other than players and items) within 64 blocks.");
            return;
        }
        say(client, "Entities within 64 blocks:");
        for (Map.Entry<String, int[]> en : found.entrySet()) {
            int[] v = en.getValue();
            client.player.sendMessage(Text.literal(String.format(" - %s x%d (nearest %d blocks, at %d, %d, %d)",
                en.getKey(), v[0], v[1], v[2], v[3], v[4])), false);
        }
    }
}
