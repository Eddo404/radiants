package dev.radiantalert;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.common.collect.Multimap;
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
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
import net.minecraft.util.ActionResult;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Radiant Alert: tells you about radiants, shinies, Lunala/Solgaleo and Ultra Wormholes, and can
 * optionally go and deal with them for you:
 *
 *  /radiantalert auto      walk to radiants and shinies, throw balls, wait for each result,
 *                          then send "/home us" once caught
 *  /radiantalert wormhole  walk into wormholes, send out a Pokémon after the first teleport,
 *                          then send "/home us" after the second teleport
 *                          (at the end of a wormhole it also catches an Ultra Beast with Beast Balls,
 *                          and if you die inside the wormhole it respawns you, re-equips a chestplate and
 *                          sends your first Pokémon out again with R, then carries on)
 *  /radiantalert party     show what is in your party (and which one counts as a normal Hoopa)
 *  /radiantalert items     list your inventory items and which ones the clean-up would drop
 *  /radiantalert clean     dry run: show what the clean-up would drop ('clean go' really does it)
 *  /radiantalert autoclean clean up automatically when about 5 slots are free
 *  /radiantalert hoopa     send out a normal Hoopa from your party and put it on your shoulder
 *  /radiantalert autohoopa do that automatically after you arrive somewhere
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
    private static final int REPLAN_COOLDOWN_TICKS = 5;

    // --- Wormhole run ---
    /** Key pressed after the first teleport to send out a Pokémon (Cobblemon's default is R). */
    private static final String SEND_OUT_KEY = "key.keyboard.r";
    /** The route planner aims for a spot this close (horizontal blocks) to the wormhole's centre. */
    private static final double WORMHOLE_GOAL_RADIUS = 1.5;
    /** Counts as "at the wormhole" within this distance; from there it walks straight into the centre. */
    private static final double WORMHOLE_ARRIVE_RADIUS = 1.8;
    /** The wormhole hangs above the ground, so jump ONCE when this close (blocks) to its centre. */
    private static final double WORMHOLE_JUMP_DISTANCE = 1.3;
    private static final double WORMHOLE_MAX_DISTANCE = 150;
    private static final int WORMHOLE_WALK_TIMEOUT_TICKS = 20 * 90;
    private static final int WORMHOLE_PUSH_TICKS = 100;
    /** Wait after a teleport before acting, so the new world has loaded. */
    private static final int AFTER_TELEPORT_WAIT_TICKS = 60;
    /** Give up waiting for the second teleport after this long. */
    private static final int ENCOUNTER_TIMEOUT_TICKS = 20 * 60 * 20;

    // --- Ultra Beast at the end of a wormhole ---
    /** Species that count as Ultra Beasts (lowercase). */
    private static final Set<String> ULTRA_BEASTS = Set.of(
        "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela", "kartana",
        "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon");
    /** The only ball used on them. No limit on how many. */
    private static final String BEAST_BALL_ID = "cobblemon:beast_ball";
    /**
     * The wormhole battle is automatic; once it's won you land on the spawn server and an Ultra Beast MAY appear
     * (not every time). Wait this long (ticks, 20 = 1 second) there, checking for one, before going home.
     * 15 seconds; raise it if they tend to show up later (the action log lists what was nearby each 2 seconds).
     */
    private static final int ULTRA_BEAST_WAIT_TICKS = 20 * 15;
    private static final double ULTRA_BEAST_SEARCH_RADIUS = 128;

    // --- Inventory clean-up (/radiantalert autoclean) ---
    /** Command that takes you to the drop-off spot. No slash. */
    private static final String DEPOSIT_COMMAND = "home deposit";
    /** Clean up when this many inventory slots (or fewer) are free. */
    private static final int INVENTORY_FREE_THRESHOLD = 5;
    /** Send the home command ("home us") again after everything has been dropped. */
    private static final boolean RETURN_HOME_AFTER_CLEAN = true;
    /** Ticks between each drop (20 = 1 second). */
    private static final int DROP_DELAY_TICKS = 4;
    /** Don't start another clean-up for this long (ms) after one. */
    private static final long CLEAN_COOLDOWN_MS = 120000;

    /** Keep at most this many of these items (by id); extras are dropped. */
    private static final Map<String, Integer> KEEP_AT_MOST = Map.of(
        "cobblemon:beast_ball", 64,
        "cobblemon:max_revive", 64);

    /** Dropped by exact item id. */
    private static final Set<String> JUNK_ITEM_IDS = Set.of(
        "minecraft:golden_apple", "minecraft:enchanted_golden_apple", "minecraft:echo_shard",
        "cobblemon:protein", "cobblemon:calcium", "cobblemon:iron", "cobblemon:zinc", "cobblemon:carbos",
        "cobblemon:hp_up", "cobblemon:pp_up", "cobblemon:pp_max",
        "cobblemon:rare_candy", "cobblemon:life_orb", "cobblemon:mirror_herb",
        "cobblemon:exp_candy_xl", "cobblemon:hyper_potion",
        "cobblemon:sweet_herba_mystica", "cobblemon:salty_herba_mystica", "cobblemon:spicy_herba_mystica",
        "cobblemon:sour_herba_mystica", "cobblemon:bitter_herba_mystica");

    /** Dropped when the item's display name is EXACTLY one of these (lowercase). */
    private static final Set<String> JUNK_EXACT_NAMES = Set.of(
        "protein", "calcium", "iron", "zinc", "carbos", "hp up", "pp up", "pp max",
        "rare candy", "life orb", "mirror herb", "exp. candy xl", "exp candy xl", "hyper potion");

    /** Dropped when the item's display name matches one of these (case-insensitive). */
    private static final List<Pattern> JUNK_NAME_PATTERNS = List.of(
        namePattern("\\bmemory\\b"),                          // memories
        namePattern("\\bmeteorite\\b"),                       // meteorites
        namePattern("\\bempty ring\\b"),                      // empty rings
        namePattern("\\bpots?\\b"),                           // pots (not potions)
        namePattern("^[a-z]{5,}ite( [xy])?$"),                  // mega stones, e.g. "Venusaurite", "Charizardite X"
        namePattern("nihilego jelly"),
        namePattern("gimmighoul.*pearl|pearl.*gimmighoul"),     // gimmighoul pearls
        namePattern("\\bflutes?\\b"),                         // flutes
        namePattern("eternal flower"),
        namePattern("echo shard"),
        namePattern("hyper.*berr(y|ies)"),                      // hyper berries
        namePattern("\\w+ium z$|\\bz-?crystal"),                // z crystals, e.g. "Normalium Z"
        namePattern("\\bfragments?\\b"),
        namePattern("herba mystica"),
        namePattern("sandwich"),
        namePattern("\\bfossils?\\b"),                          // fossils
        namePattern("charged apri"),                            // charged apri juice
        namePattern("golden apple"));

    /** Dropped when the item's registry id matches one of these (fossils and the vanilla ores / minerals). */
    private static final List<Pattern> JUNK_ID_PATTERNS = List.of(
        Pattern.compile("^cobblemon:.*fossil.*$"),                                                        // fossils
        Pattern.compile("^minecraft:(deepslate_)?(coal|iron|copper|gold|diamond|emerald|lapis|redstone)_ore$"), // ores
        Pattern.compile("^minecraft:(nether_gold_ore|nether_quartz_ore|ancient_debris)$"),
        Pattern.compile("^minecraft:raw_(iron|copper|gold)(_block)?$"),                                   // raw metal
        Pattern.compile("^minecraft:(iron|gold|copper|netherite)_ingot$"),                                // ingots
        Pattern.compile("^minecraft:(iron|gold)_nugget$"),
        Pattern.compile("^minecraft:(coal|iron|gold|copper|diamond|emerald|lapis|redstone|netherite)_block$"), // mineral blocks
        Pattern.compile("^minecraft:(coal|diamond|emerald|lapis_lazuli|redstone|quartz|netherite_scrap)$"));  // gems and dusts

    /** Never dropped, even though they look like a pattern above. */
    private static final Set<String> JUNK_NAME_EXCEPTIONS = Set.of("eviolite", "ender pearl");

    // --- Hoopa on your shoulder (/radiantalert autohoopa) ---
    private static final String HOOPA_SPECIES = "hoopa";
    /** A "normal" Hoopa may only have these aspects; anything else (delta, omega, unbound, ...) is not normal. */
    private static final Set<String> NORMAL_HOOPA_ASPECTS = Set.of("confined", "shiny", "genderless", "male", "female");
    /** ...and its form name must be one of these (lowercase). */
    private static final Set<String> NORMAL_HOOPA_FORMS = Set.of("normal", "confined");
    /** After arriving somewhere, wait this long (ms) before the automatic Hoopa check. */
    private static final long HOOPA_AFTER_ARRIVAL_MS = 6000;
    /** After a successful Hoopa mount, don't run the automatic check again for this long (ms). */
    private static final long HOOPA_RECHECK_MS = 15000;

    // ===============================================================================

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;
    private static final int RECORD_CHAT_LINES = 25;
    private static final int RECORD_FILE_LINES = 10000;
    private static final int AUTO_LOG_MAX_LINES = 3000;
    private static final int LISTEN_MAX_LINES = 8000;
    /** The listener leaves these out because they are just noise (their packets / entities). */
    private static final List<String> LISTEN_IGNORED_PACKET_PREFIXES = List.of(
        "chunky:", "cobblemon:set_pc_box", "cobblemon:set_party", "cobblemon:unvalidated_play_sound",
        "delta:update_balance", "delta:cosmetics", "vivecraft:", "voicechat:", "xercapaint:", "axiom:",
        "emotecraft:", "fabric:", "c:", "minecraft:register", "deltaclient:map_portraits", "deltaclient:client_settings");
    private static final Set<String> LISTEN_IGNORED_ENTITIES = Set.of(
        "minecraft:item_display", "minecraft:text_display", "minecraft:block_display", "minecraft:interaction",
        "minecraft:armor_stand", "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting",
        "minecraft:hopper_minecart", "minecraft:marker");
    private static final String SPY_NAME = "radiantalert_spy";
    private static final Pattern IDENTITY_OP = Pattern.compile("\"op\"\\s*:\\s*\"identity\"");
    private static final Pattern SERVER_NAME = Pattern.compile("\"server\"\\s*:\\s*\"([^\"]+)\"");

    private enum Task { IDLE, CATCH, WORMHOLE, CLEAN, HOOPA }
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
    private boolean wormholeJumped = false;
    private long wArrivedAt = 0;

    // ---- route following ----
    private List<BlockPos> path = List.of();
    private int pathIndex = 0;
    private Vec3d planTargetPos = null;
    private int replanCooldown = 0, failedPlans = 0, stuckTicks = 0, stuckStrikes = 0;
    private double stuckX, stuckY, stuckZ;
    private final Set<Long> blockedNodes = new HashSet<>();

    // ---- inventory clean-up ----
    private enum CStage { SEND_DEPOSIT, WAIT_ARRIVE, DROPPING }
    private record DropAction(int slot, boolean wholeStack, String itemId, String name) {}
    private boolean autoCleanEnabled = false;
    private CStage cStage = CStage.SEND_DEPOSIT;
    private int cTicks = 0, cArrivedAt = -1, dropDelay = 0, dropsDone = 0;
    private Vec3d cStartPos = Vec3d.ZERO;
    private final Deque<DropAction> dropQueue = new ArrayDeque<>();
    private long cleanCooldownUntil = 0;
    private int idleCheckTicks = 0;

    // ---- Hoopa on the shoulder ----
    private enum HStage { SELECT, PRESS, WAIT_OUT, APPROACH, SHOULDER }
    private boolean autoHoopaEnabled = false;
    private HStage hStage = HStage.SELECT;
    private int hTicks = 0, hSlot = -1, hAttempts = 0, hPrevSlot = -1, hGuiTicks = 0;
    private PokemonEntity hEntity = null;
    private long hoopaDueAt = 0, hoopaCooldownUntil = 0, hoopaMountedAt = 0;
    private boolean sneakHeld = false;
    private boolean hWheelClicked = false;

    // ---- chestplate test (/radiantalert chestplate) ----
    private int chestTestStage = 0, chestTestTicks = 0, chestTestCalls = 0;

    // ---- Ultra Beast catch, death handling, route start ----
    private boolean ubCatch = false;
    private long lastHomeSentAt = 0;
    private final Set<UUID> ubHandled = cappedSet(50);
    private boolean respawnPending = false;
    private boolean respawnContinuesRun = false;
    private long lastRespawnAt = 0;
    private int chestAttempts = 0;
    private Screen lastUnblockScreen = null;
    private int screenOpenTicks = 0;
    private long lastEndBattleAt = 0;
    private int deathTicks = 0, respawnTicks = 0;
    private Vec3d routeStart = Vec3d.ZERO;

    // ---- action log ----
    private int autoLogLines = 0;

    // ---- listener (/radiantalert listen) ----
    private volatile boolean listening = false;
    private long listenStart = 0;
    private int listenLines = 0;
    private String lastListenText = null;
    private int listenRepeats = 0;
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
                    autoCleanEnabled = false;
                    autoHoopaEnabled = false;
                    ubCatch = false;
                    abortCurrent(c, "Stopped.");
                    say(c, "All automatic features are OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("party").executes(ctx -> {
                    showParty(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("items").executes(ctx -> {
                    dumpItems(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("chestplate").executes(ctx -> {
                    startChestTest(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("clean")
                    .executes(ctx -> {
                        showCleanPlan(MinecraftClient.getInstance());
                        return 1;
                    })
                    .then(ClientCommandManager.literal("go").executes(ctx -> {
                        startClean(MinecraftClient.getInstance(), false);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("autoclean").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoCleanEnabled = !autoCleanEnabled;
                    say(c, autoCleanEnabled
                        ? "Auto-clean ON: when " + INVENTORY_FREE_THRESHOLD + " or fewer slots are free I'll go to /" + DEPOSIT_COMMAND
                            + " and drop the junk. Check the list first with /radiantalert clean."
                        : "Auto-clean OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("hoopa").executes(ctx -> {
                    startHoopa(MinecraftClient.getInstance(), true);
                    return 1;
                }))
                .then(ClientCommandManager.literal("autohoopa").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoHoopaEnabled = !autoHoopaEnabled;
                    if (autoHoopaEnabled) hoopaDueAt = System.currentTimeMillis() + 2000;
                    say(c, autoHoopaEnabled
                        ? "Auto-Hoopa ON: after you arrive somewhere I'll put a normal Hoopa from your party on your shoulder."
                        : "Auto-Hoopa OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    say(c, "packet watcher: " + spyInstalled + " | recording: " + recording + " | listening: " + listening
                        + " | auto-catch: " + autoCatchEnabled + " | auto-wormhole: " + autoWormholeEnabled
                        + " | auto-clean: " + autoCleanEnabled + " | auto-hoopa: " + autoHoopaEnabled
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

        if (++idleCheckTicks >= 20) {
            idleCheckTicks = 0;
            idleChecks(client);
            chestCheck(client);
        }
        if (chestTestStage != 0) chestTestTick(client);

        // Dying: respawn, put a chestplate back on, and
        //  - inside the wormhole (a life is lost, the run goes on): send your first Pokémon out again with R;
        //  - anywhere else during a wormhole run: the run is over, so go home.
        if (client.currentScreen instanceof DeathScreen && (task == Task.WORMHOLE || autoWormholeEnabled)) {
            if (++deathTicks >= 20) {
                deathTicks = 0;
                boolean insideWormhole = task == Task.WORMHOLE && (wStage == WStage.ARRIVED || wStage == WStage.ENCOUNTER);
                respawnContinuesRun = insideWormhole;
                autoLog("DEATH detected - respawning (task=" + task + ", insideWormhole=" + insideWormhole + ")");
                if (insideWormhole) {
                    say(client, "You died in the wormhole - respawning, then sending your first Pokémon out again.");
                } else {
                    ubCatch = false;
                    abortCurrent(client, "You died - respawning.");
                }
                lastRespawnAt = System.currentTimeMillis();
                client.player.requestRespawn();
                client.setScreen(null);
                respawnPending = true;
                respawnTicks = 0;
            }
        } else {
            deathTicks = 0;
        }
        if (respawnPending && client.currentScreen == null && client.player.isAlive()) {
            if (++respawnTicks >= 40) {
                respawnPending = false;
                lastRespawnAt = System.currentTimeMillis();
                ensureChestplate(client);
                if (respawnContinuesRun && task == Task.WORMHOLE) {
                    trySelectPartySlot(0);        // the first Pokémon
                    pressSendOutKey();            // R
                    wStage = WStage.ENCOUNTER;    // keep waiting for the battle to finish
                    wTicks = 0;
                    autoLog("DEATH recovered: sent out the first Pokémon again");
                    say(client, "Sent your first Pokémon out again.");
                } else {
                    sendHome(client);
                }
            }
        }

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

            // Ultra Beasts don't spawn naturally in Ultra Space (the ones there can't be caught): ignore them there
            if (ULTRA_BEASTS.contains(species) && inUltraSpace(client)) {
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
        if (client.currentScreen != null) {
            // crouch + right-click on your Pokémon opens Cobblemon's interaction wheel: handle it instead of freezing behind it
            if (task == Task.HOOPA && hStage == HStage.SHOULDER && hAttempts >= 2 && isCobblemonInteractScreen(client.currentScreen)) {
                hoopaScreen(client);
                return;
            }
            releaseKeys(client);
            unblockScreen(client); // close chat / menus, or end a battle, that would stop us from moving
            return;
        }
        lastUnblockScreen = null;
        screenOpenTicks = 0;

        switch (task) {
            case CATCH -> catchTick(client, player);
            case WORMHOLE -> wormholeTick(client, player);
            case CLEAN -> cleanTick(client, player);
            case HOOPA -> hoopaTick(client, player);
            default -> { }
        }
    }

    private void abortCurrent(MinecraftClient client, String message) {
        switch (task) {
            case CATCH -> stopAuto(client, message);
            case WORMHOLE -> stopWormhole(client, message);
            case CLEAN -> stopClean(client, message);
            case HOOPA -> stopHoopa(client, message);
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
        if (!text.startsWith("[RadiantAlert]") && !text.startsWith("[rec]")) listenLog("CHAT " + text);
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
        if (autoHoopaEnabled) hoopaDueAt = now + HOOPA_AFTER_ARRIVAL_MS;
        if (task == Task.CLEAN && cStage == CStage.WAIT_ARRIVE) {
            cArrivedAt = cTicks;
            autoLog("CLEAN arrived at the drop-off");
        } else if (task == Task.HOOPA) {
            stopHoopa(client, "The server changed - stopping the Hoopa routine.");
        }
        if (task == Task.CATCH) {
            ubCatch = false;
            stopAuto(client, "The server changed - stopping the catch.");
        } else if (task == Task.WORMHOLE && (respawnPending || now - lastRespawnAt < 6000)) {
            autoLog("HOP ignored (just respawned)");
        } else if (task == Task.WORMHOLE) {
            switch (wStage) {
                case WALK, PUSH -> {
                    wStage = WStage.ARRIVED;
                    wTicks = 0;
                    wArrivedAt = now;
                    releaseKeys(client);
                    say(client, "Teleported! Waiting a moment, then sending out a Pokémon...");
                }
                case ARRIVED, ENCOUNTER -> {
                    // left the wormhole: go home (ignore the tail end of the first teleport)
                    if (wStage == WStage.ENCOUNTER || now - wArrivedAt > 8000) {
                        wStage = WStage.FINISHED;
                        wTicks = 0;
                        say(client, "Left the wormhole - sending /" + HOME_COMMAND + " shortly.");
                    }
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
        long now = System.currentTimeMillis();
        if (now - lastHomeSentAt < 5000) return; // already sent a moment ago
        lastHomeSentAt = now;
        homeCooldownUntil = now + HOME_COOLDOWN_MS;
        client.player.networkHandler.sendChatCommand(HOME_COMMAND);
        autoLog("SENT /" + HOME_COMMAND);
        say(client, "Sent /" + HOME_COMMAND);
    }

    // ============================== Catching ==============================

    private void startCatch(MinecraftClient client, PokemonEntity e, boolean radiant) {
        beginCatch(client, e, radiant, false);
    }

    /** An Ultra Beast at the end of a wormhole: Beast Balls only, no ball limit, then home. */
    private void startUltraBeastCatch(MinecraftClient client, PokemonEntity e) {
        beginCatch(client, e, false, true);
    }

    private void beginCatch(MinecraftClient client, PokemonEntity e, boolean radiant, boolean ultraBeast) {
        if (client.player.distanceTo(e) > MAX_APPROACH_DISTANCE) {
            say(client, "Too far away to chase (more than " + (int) MAX_APPROACH_DISTANCE + " blocks).");
            if (ultraBeast) sendHome(client);
            return;
        }
        task = Task.CATCH;
        ubCatch = ultraBeast;
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
        autoLog("CATCH start " + targetName + " radiant=" + radiant + " ultraBeast=" + ultraBeast);
        if (ultraBeast) {
            client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);
            say(client, "ULTRA BEAST! Heading to " + targetName + " - Beast Balls until it's caught.");
        } else {
            say(client, "Heading to " + (radiant ? "Radiant " : "Shiny ") + targetName
                + (radiant ? " (Master/Ancient Origin balls allowed)." : " (no Master/Ancient Origin balls).")
                + " Open chat and run /radiantalert stop to cancel.");
        }
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
        if (!allowPremiumBalls && !ubCatch && throwsDone >= MAX_BALLS_SHINY) {
            // a plain shiny only gets MAX_BALLS_SHINY balls; then give up and go home
            stopAuto(client, "Used " + MAX_BALLS_SHINY + " balls on " + targetName + " - giving up.");
            sendHome(client);
            return;
        }
        if (switchWait > 0) { switchWait--; return; }
        if (angleError(player, current) > 4.0f) return; // wait until we're aimed at it

        int slot = findBallIndex(player);
        if (slot < 0) {
            stopAuto(client, ubCatch
                ? "No Beast Ball anywhere in your inventory!"
                : allowPremiumBalls
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
        if (ubCatch) {
            for (int i = 0; i < 36; i++) {
                ItemStack s = player.getInventory().getStack(i);
                if (!s.isEmpty() && itemIdOf(s).equals(BEAST_BALL_ID)) return i;
            }
            return -1;
        }
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
        boolean goHome = ubCatch; // an Ultra Beast catch always ends by going home, however it ended
        ubCatch = false;
        task = Task.IDLE;
        target = null;
        phase = Phase.READY;
        ball = null;
        path = List.of();
        zoneActive = false;
        releaseKeys(client);
        autoLog("CATCH stop: " + message);
        say(client, message);
        if (goHome) sendHome(client);
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
        ensureChestplate(client);
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
                    wormholeJumped = false;
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
                // walk toward the centre and jump exactly once when we're right at it
                double hd = Math.hypot(player.getX() - wormholePos.x, player.getZ() - wormholePos.z);
                float yaw = (float) Math.toDegrees(Math.atan2(wormholePos.z - player.getZ(), wormholePos.x - player.getX())) - 90.0f;
                player.setYaw(MathHelper.lerpAngleDegrees(0.5f, player.getYaw(), yaw));

                boolean jump = false;
                if (!wormholeJumped && player.isOnGround() && hd <= WORMHOLE_JUMP_DISTANCE) {
                    jump = true;
                    wormholeJumped = true;
                    autoLog("WORMHOLE jump");
                }
                client.options.forwardKey.setPressed(hd > 0.35);
                client.options.sprintKey.setPressed(false);
                client.options.jumpKey.setPressed(jump);
                keysHeld = true;
            }
            case ARRIVED -> {
                releaseKeys(client);
                if (wTicks == 10) ensureChestplate(client);
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
                if (wTicks == 1) {
                    say(client, "Checking for an Ultra Beast (up to " + (ULTRA_BEAST_WAIT_TICKS / 20) + " seconds)...");
                }
                if (wTicks % 40 == 0) logNearbyPokemon(client);
                // an Ultra Beast may appear on the spawn server: it MUST be caught (Beast Balls) before going home
                PokemonEntity beast = findUltraBeast(client);
                if (beast != null) {
                    ubHandled.add(beast.getUuid());
                    task = Task.IDLE;
                    path = List.of();
                    startUltraBeastCatch(client, beast);
                    return;
                }
                if (wTicks >= ULTRA_BEAST_WAIT_TICKS) {
                    stopWormhole(client, "Wormhole run finished (no Ultra Beast showed up).");
                    sendHome(client);
                }
            }
        }
    }

    /** The nearest wild Ultra Beast around you that we haven't already handled. */
    private PokemonEntity findUltraBeast(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        if (inUltraSpace(client)) return null; // they don't spawn naturally there
        PokemonEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PokemonEntity pe : client.world.getEntitiesByClass(PokemonEntity.class,
                client.player.getBoundingBox().expand(ULTRA_BEAST_SEARCH_RADIUS),
                x -> x.getOwnerUuid() == null && !x.isRemoved() && !ubHandled.contains(x.getUuid())
                    && ULTRA_BEASTS.contains(x.getPokemon().getSpecies().getName().toLowerCase(Locale.ROOT)))) {
            double d = pe.squaredDistanceTo(client.player);
            if (d < bestDist) { bestDist = d; best = pe; }
        }
        return best;
    }

    /**
     * Makes sure a chestplate is worn. Each call tries one thing and it is called again every second during a
     * wormhole run until the chestplate is on: (1) shift-click it into the armour slot, (2) bring it to the
     * hotbar, (3) right-click it from the hotbar (which equips it).
     */
    private void ensureChestplate(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;
        if (!player.getEquippedStack(EquipmentSlot.CHEST).isEmpty()) { chestAttempts = 0; return; }

        int found = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            if (itemIdOf(s).endsWith("_chestplate") || itemNameOf(s).toLowerCase(Locale.ROOT).contains("chestplate")) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            if (chestAttempts++ % 10 == 0) {
                autoLog("CHEST no chestplate found in the inventory");
                say(client, "You're not wearing a chestplate and I couldn't find one in your inventory!");
            }
            return;
        }

        int step = chestAttempts++ % 3;
        ItemStack stack = player.getInventory().getStack(found);
        int screenSlot = found < 9 ? 36 + found : found;
        autoLog("CHEST step " + (step + 1) + ": " + itemIdOf(stack) + " from inventory slot " + found);
        if (step == 0) {
            say(client, "Putting on your chestplate (" + itemNameOf(stack) + ").");
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, screenSlot, 0,
                SlotActionType.QUICK_MOVE, player);
        } else if (step == 1) {
            if (found >= 9) {
                client.interactionManager.clickSlot(player.playerScreenHandler.syncId, screenSlot,
                    player.getInventory().selectedSlot, SlotActionType.SWAP, player);
            } else {
                player.getInventory().selectedSlot = found;
            }
        } else {
            ItemStack held = player.getMainHandStack();
            if (!held.isEmpty() && (itemIdOf(held).endsWith("_chestplate")
                    || itemNameOf(held).toLowerCase(Locale.ROOT).contains("chestplate"))) {
                client.interactionManager.interactItem(player, Hand.MAIN_HAND); // right-click to wear it
            } else if (found < 9) {
                player.getInventory().selectedSlot = found;
            }
        }
    }

    /**
     * /radiantalert chestplate: tests the equipping. If you are wearing a chestplate it is taken off first,
     * then the same routine the wormhole run uses puts it back on, and the result is reported.
     */
    private void startChestTest(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;
        if (client.currentScreen != null) { say(client, "Close any open menu first."); return; }
        if (task != Task.IDLE) { say(client, "Busy with something else right now."); return; }
        chestAttempts = 0;
        chestTestCalls = 0;
        chestTestTicks = 0;
        if (!player.getEquippedStack(EquipmentSlot.CHEST).isEmpty()) {
            say(client, "Chestplate test: taking your chestplate off first...");
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, 6, 0, SlotActionType.QUICK_MOVE, player);
            chestTestStage = 1;
        } else {
            say(client, "Chestplate test: no chestplate is worn - putting one on.");
            chestTestStage = 2;
        }
    }

    private void chestTestTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null) { chestTestStage = 0; return; }
        chestTestTicks++;
        boolean worn = !player.getEquippedStack(EquipmentSlot.CHEST).isEmpty();

        if (chestTestStage == 1) {                       // waiting for it to come off
            if (!worn) {
                say(client, "Chestplate test: it's off. Now putting it back on...");
                chestTestStage = 2;
                chestTestTicks = 0;
                chestAttempts = 0;
            } else if (chestTestTicks > 40) {
                say(client, "Chestplate test: couldn't take the chestplate off (is your inventory full?).");
                chestTestStage = 0;
            }
            return;
        }

        // stage 2: putting it on, one step every half second
        if (worn) {
            say(client, "Chestplate test PASSED: the chestplate is on (after " + chestTestCalls + " step(s)).");
            autoLog("CHEST test passed after " + chestTestCalls + " step(s)");
            chestTestStage = 0;
            return;
        }
        if (chestTestTicks % 10 == 1) {
            if (chestTestCalls >= 6) {
                say(client, "Chestplate test FAILED: it never went on. See the CHEST lines in radiantalert-auto.txt.");
                autoLog("CHEST test failed");
                chestTestStage = 0;
                return;
            }
            chestTestCalls++;
            ensureChestplate(client);
        }
    }

    /** Once a second during a wormhole run: if the chest slot is empty, try to put a chestplate on. */
    private void chestCheck(MinecraftClient client) {
        if (task != Task.WORMHOLE || client.currentScreen != null || client.player == null) return;
        if (client.player.getEquippedStack(EquipmentSlot.CHEST).isEmpty()) ensureChestplate(client);
        else chestAttempts = 0;
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

        // just got hit (knockback): the old route is no good, plan a new one straight away
        if (player.hurtTime >= 8) {
            path = List.of();
            replanCooldown = 0;
            stuckTicks = 0;
            stuckStrikes = 0;
        }

        boolean noPath = path.isEmpty() || pathIndex >= path.size();
        boolean goalMoved = planTargetPos == null || goal.squaredDistanceTo(planTargetPos) > 6.25;
        boolean offPath = !noPath && isOffPath(player,
            pathIndex == 0 ? routeStart : Vec3d.ofBottomCenter(path.get(pathIndex - 1)), path.get(pathIndex));

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

        // move on to the next waypoint once we're standing on it, or if we've already run past it
        BlockPos wp = path.get(pathIndex);
        while (true) {
            double hdCur = horizontalDistance(player, wp);
            boolean reached = hdCur < 0.6 && Math.abs(wp.getY() - player.getY()) < 1.5;
            boolean passed = false;
            if (!reached && pathIndex + 1 < path.size()) {
                BlockPos next = path.get(pathIndex + 1);
                passed = next.getY() == wp.getY() && hdCur < 1.2 && horizontalDistance(player, next) < hdCur;
            }
            if (!reached && !passed) break;
            pathIndex++;
            if (pathIndex >= path.size()) { path = List.of(); releaseKeys(client); return; }
            wp = path.get(pathIndex);
        }

        // barely moving for half a second: re-plan; if it keeps happening, avoid the square just ahead of us
        if (++stuckTicks >= 10) {
            double moved = Math.abs(player.getX() - stuckX) + Math.abs(player.getY() - stuckY) + Math.abs(player.getZ() - stuckZ);
            stuckTicks = 0;
            stuckX = player.getX();
            stuckY = player.getY();
            stuckZ = player.getZ();
            if (moved < 0.25) {
                stuckStrikes++;
                if (stuckStrikes >= 8) { abortCurrent(client, "Got stuck and couldn't find a way out - stopping."); return; }
                if (stuckStrikes >= 2) {
                    double sx = wp.getX() + 0.5 - player.getX();
                    double sz = wp.getZ() + 0.5 - player.getZ();
                    double len = Math.max(0.001, Math.hypot(sx, sz));
                    blockedNodes.add(BlockPos.asLong(MathHelper.floor(player.getX() + sx / len * 0.9),
                        MathHelper.floor(player.getY() + 0.05), MathHelper.floor(player.getZ() + sz / len * 0.9)));
                    say(client, "Stuck - trying a different route.");
                }
                path = List.of();
                replanCooldown = 0;
                releaseKeys(client);
                return;
            } else {
                stuckStrikes = 0;
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

        // keep walking through turns; only stop to turn on the spot when facing almost the wrong way
        boolean forward = yawError < 100.0f;
        Vec3d vel = player.getVelocity();
        boolean barelyMoving = vel.x * vel.x + vel.z * vel.z < 0.0025;
        // jump for planned one-block steps, in water, or when pushing against something without moving
        boolean jump = player.isTouchingWater()
            || (player.isOnGround() && ((dy > 0.3 && hd < 1.6) || (player.horizontalCollision && barelyMoving && forward)));
        boolean sprint = forward && hd > 2.5 && dy < 0.3 && yawError < 15.0f;

        client.options.forwardKey.setPressed(forward);
        client.options.sprintKey.setPressed(sprint);
        client.options.jumpKey.setPressed(jump);
        keysHeld = true;
    }

    private static double horizontalDistance(ClientPlayerEntity player, BlockPos p) {
        double dx = p.getX() + 0.5 - player.getX();
        double dz = p.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Off the route = far from the straight line between the previous waypoint and the current one. */
    private static boolean isOffPath(ClientPlayerEntity player, Vec3d from, BlockPos wp) {
        double ax = from.x, az = from.z, bx = wp.getX() + 0.5, bz = wp.getZ() + 0.5;
        double abx = bx - ax, abz = bz - az;
        double len2 = abx * abx + abz * abz;
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((player.getX() - ax) * abx + (player.getZ() - az) * abz) / len2));
        double off = Math.hypot(player.getX() - (ax + abx * t), player.getZ() - (az + abz * t));
        return off > 2.5 || Math.abs(wp.getY() - player.getY()) > 3.5;
    }

    /** Plans a new route. Returns false if the current task was given up. */
    private boolean replan(MinecraftClient client, ClientPlayerEntity player, Vec3d goal, double goalEyeY, boolean ring) {
        planTargetPos = goal;
        routeStart = player.getPos();
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

                if (cur != first && isGoal(cur.pos)) return new Plan(Status.PATH, smooth(start, build(cur)));

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
            if (best != first && bestH < startH - 1.0) return new Plan(Status.PARTIAL, smooth(start, build(best)));
            return new Plan(Status.FAIL, List.of());
        }

        /**
         * A* routes on a grid come out as zig-zags. This skips waypoints whenever a straight, level
         * line between two of them is clear (body-width wide), so the walk is smooth.
         */
        private List<BlockPos> smooth(BlockPos start, List<BlockPos> route) {
            List<BlockPos> out = new ArrayList<>();
            BlockPos from = start;
            int i = 0;
            while (i < route.size()) {
                int best = i;
                for (int j = i + 1; j < route.size() && j <= i + 14; j++) {
                    if (route.get(j).getY() != from.getY() || route.get(j - 1).getY() != from.getY()) break;
                    if (!straightWalk(from, route.get(j))) break;
                    best = j;
                }
                out.add(route.get(best));
                from = route.get(best);
                i = best + 1;
            }
            return out;
        }

        /** True if you can walk in a straight line from a to b (same level): every point along it, body width included, is standable. */
        private boolean straightWalk(BlockPos a, BlockPos b) {
            double ax = a.getX() + 0.5, az = a.getZ() + 0.5, bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
            int steps = (int) Math.ceil(Math.hypot(bx - ax, bz - az) / 0.3);
            int y = a.getY();
            for (int k = 1; k < steps; k++) {
                double t = (double) k / steps;
                double x = ax + (bx - ax) * t, z = az + (bz - az) * t;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        if (!standable(MathHelper.floor(x + sx * 0.3), y, MathHelper.floor(z + sz * 0.3))) return false;
                    }
                }
            }
            return true;
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
                    boolean listen = listening && !info.id().startsWith("particle:") && !isListenNoise(info.id());
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
        Pokemon pokemonValue = null;   // for party packets
        int slotValue = -1;
        boolean thisPlayersParty = true;
        for (Class<?> k = payload.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(payload);
                    if (v instanceof Pokemon pk) {
                        pokemonValue = pk;
                    } else if (v != null && v.getClass().getName().contains("PartyPosition")) {
                        slotValue = readSlot(v);
                    } else if (v instanceof Boolean flag && f.getName().toLowerCase(Locale.ROOT).contains("player")) {
                        thisPlayersParty = flag;
                    }
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
        capturePartyPacket(id, pokemonValue, slotValue, thisPlayersParty);
        return new Info(id, strings, pos, 0);
    }

    private static boolean matchesWormhole(Info info) {
        return containsAny(info.id() + " " + String.join(" ", info.strings()), WORMHOLE_PACKET_WORDS);
    }

    private void handleInfo(Info info, boolean wormhole, boolean identity) {
        if (identity) onIdentityInfo(info);
        if (listening && !info.id().startsWith("particle:") && !isListenNoise(info.id())) {
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

    // ============================== Menus in the way ==============================

    /**
     * While a task is running and a screen is open: close chat (only if nothing is typed in it) and menus like
     * the inventory or Cobblemon's screens, as if pressing Esc; for a Cobblemon battle screen send /endbattle.
     * Loading screens and the like are left alone. While waiting for the automatic wormhole battle, nothing is touched.
     */
    private void unblockScreen(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null) {
            lastUnblockScreen = null;
            screenOpenTicks = 0;
            return;
        }
        if (screen != lastUnblockScreen) {
            lastUnblockScreen = screen;
            screenOpenTicks = 0;
        }
        screenOpenTicks++;

        if (task == Task.WORMHOLE && wStage == WStage.ENCOUNTER) return; // the battle is supposed to be running

        String cls = screen.getClass().getName();
        boolean cobblemon = cls.startsWith("com.cobblemon");
        boolean battle = cobblemon && cls.toLowerCase(Locale.ROOT).contains("battle");

        if (battle) {
            boolean mayEnd = task != Task.WORMHOLE || wStage == WStage.WALK || wStage == WStage.PUSH || wStage == WStage.FINISHED;
            long now = System.currentTimeMillis();
            if (mayEnd && screenOpenTicks >= 20 && now - lastEndBattleAt > 4000 && client.player != null) {
                lastEndBattleAt = now;
                client.player.networkHandler.sendChatCommand("endbattle");
                autoLog("MENU battle screen in the way: sent /endbattle (" + cls + ")");
                say(client, "In a battle - sending /endbattle so I can carry on.");
            }
            return;
        }

        if (screen instanceof ChatScreen chat) {
            String typed = chatScreenText(chat);
            boolean ready = typed == null ? screenOpenTicks >= 60 : typed.isEmpty() && screenOpenTicks >= 40;
            if (ready) {
                autoLog("MENU closing the chat screen");
                screen.close();
            }
            return;
        }

        if (screen instanceof HandledScreen<?> || screen instanceof GameMenuScreen || cobblemon) {
            if (screenOpenTicks >= 10) {
                autoLog("MENU closing " + cls);
                screen.close();
            }
        }
        // anything else (loading screens, the death screen, ...) is left alone
    }

    /** What is typed in the chat box right now (null if it can't be read). */
    private static String chatScreenText(ChatScreen screen) {
        for (Field f : ChatScreen.class.getDeclaredFields()) {
            if (f.getType() != TextFieldWidget.class) continue;
            try {
                f.setAccessible(true);
                Object widget = f.get(screen);
                if (widget instanceof TextFieldWidget field) return field.getText();
            } catch (Throwable ignored) {
                // try the next field
            }
        }
        return null;
    }

    /** Writes the wild Pokémon around you (species @ distance) to the action log, to see what shows up at the end of a wormhole. */
    private void logNearbyPokemon(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        List<String> found = new ArrayList<>();
        for (PokemonEntity pe : client.world.getEntitiesByClass(PokemonEntity.class,
                client.player.getBoundingBox().expand(ULTRA_BEAST_SEARCH_RADIUS),
                x -> x.getOwnerUuid() == null && !x.isRemoved())) {
            found.add(pe.getPokemon().getSpecies().getName() + "@" + Math.round(pe.distanceTo(client.player)));
        }
        autoLog("UB CHECK +" + (wTicks / 20) + "s dim=" + client.world.getRegistryKey().getValue()
            + " wild Pokémon nearby: " + found);
    }

    // ============================== Idle checks ==============================

    private static Pattern namePattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /** Once a second while idle: start a clean-up or the Hoopa routine if one is due. */
    private void idleChecks(MinecraftClient client) {
        if (!canStartTask() || client.currentScreen != null || client.player == null) return;
        long now = System.currentTimeMillis();
        if (autoCleanEnabled && now >= cleanCooldownUntil && freeSlots(client.player) <= INVENTORY_FREE_THRESHOLD) {
            startClean(client, true);
            return;
        }
        if (autoHoopaEnabled && hoopaDueAt != 0 && now >= hoopaDueAt && now >= hoopaCooldownUntil
                && now - hoopaMountedAt > HOOPA_RECHECK_MS) {
            hoopaDueAt = 0;
            startHoopa(client, false);
        }
    }

    // ============================== Party ==============================

    private static Object invoke(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        throw new NoSuchMethodException(String.join("/", names) + " on " + target.getClass().getName());
    }

    /** Cobblemon's client-side storage object (holds your party). Found by name so a version change can't break the build. */
    private static Object partyStorage() throws Exception {
        Class<?> clientClass = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
        try {
            return invoke(clientClass.getField("INSTANCE").get(null), "getStorage");
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            return clientClass.getField("storage").get(null);
        }
    }

    /** Your party in slot order; an empty slot is null. */
    private static List<Pokemon> readParty() throws Exception {
        Throwable firstError = null;

        // 1) Cobblemon's own client-side storage
        try {
            List<Pokemon> fromStorage = readPartyFromStorage();
            if (fromStorage.stream().anyMatch(Objects::nonNull)) {
                partySource = "Cobblemon's client storage";
                return fromStorage;
            }
        } catch (Throwable t) {
            firstError = t;
        }

        // 2) search the objects reachable from CobblemonClient for a list of Pokémon
        try {
            List<Pokemon> found = searchPartyList();
            if (found != null && found.stream().anyMatch(Objects::nonNull)) {
                partySource = "an object search";
                return found;
            }
        } catch (Throwable t) {
            if (firstError == null) firstError = t;
        }

        // 3) the party packets the server sent when you joined (always available)
        if (!PACKET_PARTY.isEmpty()) {
            List<Pokemon> out = new ArrayList<>();
            for (int i = 0; i < 6; i++) out.add(PACKET_PARTY.get(i));
            partySource = "the party packets the server sent";
            return out;
        }
        throw new IllegalStateException("couldn't find your party (" + firstError
            + "; no party packets seen yet - switch servers or rejoin, then try again)");
    }

    private static List<Pokemon> readPartyFromStorage() throws Exception {
        Object party = invoke(partyStorage(), "getMyParty");
        Object slots;
        try {
            slots = invoke(party, "getSlots");
        } catch (NoSuchMethodException e) {
            slots = party;
        }
        if (!(slots instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("the party is not a list: " + slots);
        }
        List<Pokemon> out = new ArrayList<>();
        for (Object o : iterable) out.add(o instanceof Pokemon p ? p : null);
        return out;
    }

    /** Selects a party slot (the one the send-out key throws). Returns false if Cobblemon's API wasn't found. */
    private static boolean trySelectPartySlot(int slot) {
        try {
            Object storage = partyStorage();
            storage.getClass().getMethod("setSelectedSlot", int.class).invoke(storage, slot);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ultra Beasts don't spawn naturally in Ultra Space (the dimension "ultraspace:ultraspace"). */
    private static boolean inUltraSpace(MinecraftClient client) {
        return client.world != null && client.world.getRegistryKey().getValue().getNamespace().equals("ultraspace");
    }

    /** Where the last successful party read came from. */
    private static volatile String partySource = "?";

    /** The party as the server sent it (slot -> Pokémon). Filled from the party packets as they arrive. */
    private static final Map<Integer, Pokemon> PACKET_PARTY = new ConcurrentHashMap<>();

    private static int readSlot(Object position) {
        for (Field f : position.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            try {
                f.setAccessible(true);
                return f.getInt(position);
            } catch (Throwable ignored) {
                // try the next field
            }
        }
        return -1;
    }

    private static void capturePartyPacket(String id, Pokemon pokemon, int slot, boolean thisPlayersParty) {
        switch (id) {
            case "cobblemon:initialize_party" -> {
                if (thisPlayersParty) PACKET_PARTY.clear();
            }
            case "cobblemon:set_party_pokemon" -> {
                if (pokemon == null) return;
                int target = slot;
                if (target < 0) {
                    target = 0;
                    while (PACKET_PARTY.containsKey(target)) target++;
                }
                PACKET_PARTY.put(target, pokemon);
            }
            case "cobblemon:remove_party_pokemon" -> {
                if (slot >= 0) PACKET_PARTY.remove(slot);
            }
            default -> { }
        }
    }

    /** Last resort: walks the objects reachable from CobblemonClient looking for a list that holds Pokémon, preferring ones named "party". */
    private static List<Pokemon> searchPartyList() throws Exception {
        Class<?> root = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
        Deque<Object[]> queue = new ArrayDeque<>(); // {object, path, depth}
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            queue.add(new Object[]{root.getField("INSTANCE").get(null), "CobblemonClient", 0});
        } catch (Throwable ignored) {
            // not a Kotlin object: static fields below
        }
        for (Field f : root.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) queue.add(new Object[]{v, "CobblemonClient." + f.getName(), 1});
            } catch (Throwable ignored) {
                // skip
            }
        }

        List<Pokemon> best = null;
        int bestScore = -1;
        int budget = 4000;
        while (!queue.isEmpty() && budget-- > 0) {
            Object[] item = queue.poll();
            Object obj = item[0];
            String path = (String) item[1];
            int depth = (Integer) item[2];
            if (obj == null || obj instanceof Pokemon || !visited.add(obj)) continue;

            Iterable<?> items = null;
            if (obj instanceof Iterable<?> it) items = it;
            else if (obj instanceof Map<?, ?> m) items = m.values();
            else if (obj instanceof Object[] arr) items = Arrays.asList(arr);

            if (items != null) {
                List<Pokemon> list = new ArrayList<>();
                boolean onlyPokemon = true;
                boolean any = false;
                int looked = 0;
                for (Object o : items) {
                    if (o instanceof Pokemon pk) { list.add(pk); any = true; }
                    else if (o == null) list.add(null);
                    else {
                        onlyPokemon = false;
                        if (depth < 5 && looked++ < 8) queue.add(new Object[]{o, path + "[]", depth + 1});
                    }
                }
                if (onlyPokemon && any) {
                    String lower = path.toLowerCase(Locale.ROOT);
                    int score = (lower.contains("party") ? 10 : 1) + (list.size() <= 6 ? 3 : 0) - (lower.contains("pc") ? 5 : 0);
                    if (score > bestScore) { bestScore = score; best = list; }
                }
                continue;
            }

            if (depth >= 4 || !obj.getClass().getName().startsWith("com.cobblemon")) continue;
            for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) queue.add(new Object[]{v, path + "." + f.getName(), depth + 1});
                    } catch (Throwable ignored) {
                        // skip
                    }
                }
            }
        }
        return best;
    }

    /** Sends Cobblemon's own "send out Pokémon" packet for a party slot (what pressing R does). Returns false if that didn't work. */
    private static boolean trySendOutPacket(int slot) {
        try {
            Class<?> c = Class.forName("com.cobblemon.mod.common.net.messages.server.SendOutPokemonPacket");
            Object packet = c.getConstructor(int.class).newInstance(slot);
            if (!(packet instanceof CustomPayload payload)) return false;
            ClientPlayNetworking.send(payload);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The Pokémon's current state as Cobblemon names it: roughly Inactive (in the party), Active (out) or Shouldered. */
    private static String pokemonState(Pokemon p) {
        try {
            Object state = invoke(p, "getState");
            return state == null ? "?" : state.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describePokemon(Pokemon p) {
        return p.getSpecies().getName() + " | form=" + p.getForm().getName() + " | aspects=" + p.getAspects()
            + " | shiny=" + p.getShiny() + " | lvl " + p.getLevel() + " | state=" + pokemonState(p);
    }

    private static boolean isNormalHoopa(Pokemon p) {
        if (!p.getSpecies().getName().equalsIgnoreCase(HOOPA_SPECIES)) return false;
        if (!NORMAL_HOOPA_FORMS.contains(p.getForm().getName().toLowerCase(Locale.ROOT))) return false;
        for (String aspect : p.getAspects()) {
            if (!NORMAL_HOOPA_ASPECTS.contains(aspect.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private void showParty(MinecraftClient client) {
        if (client.player == null) return;
        try {
            List<Pokemon> party = readParty();
            say(client, "Your party (read from " + partySource + "):");
            for (int i = 0; i < party.size(); i++) {
                Pokemon p = party.get(i);
                String line = " " + (i + 1) + ". " + (p == null ? "(empty)"
                    : describePokemon(p) + (isNormalHoopa(p) ? "   <- normal Hoopa" : ""));
                client.player.sendMessage(Text.literal(line), false);
                autoLog("PARTY" + line);
            }
        } catch (Throwable t) {
            say(client, "Couldn't read your party: " + t);
            autoLog("PARTY failed: " + t);
            logPartyApi();
        }
    }

    /** When reading the party fails, write down what Cobblemon's client classes really look like, so it can be fixed. */
    private void logPartyApi() {
        try {
            Class<?> c = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
            StringBuilder sb = new StringBuilder("API CobblemonClient methods/fields:");
            for (java.lang.reflect.Method m : c.getMethods()) sb.append(' ').append(m.getName());
            for (Field f : c.getFields()) sb.append(" field:").append(f.getName());
            autoLog(sb.toString());
            Object storage = partyStorage();
            StringBuilder sb2 = new StringBuilder("API storage " + storage.getClass().getName() + " methods:");
            for (java.lang.reflect.Method m : storage.getClass().getMethods()) sb2.append(' ').append(m.getName());
            autoLog(sb2.toString());
            Object party = invoke(storage, "getMyParty");
            StringBuilder sb3 = new StringBuilder("API party " + party.getClass().getName() + " methods:");
            for (java.lang.reflect.Method m : party.getClass().getMethods()) sb3.append(' ').append(m.getName());
            autoLog(sb3.toString());
        } catch (Throwable t) {
            autoLog("API listing failed: " + t);
        }
    }

    // ============================== Inventory clean-up ==============================

    private static int freeSlots(ClientPlayerEntity player) {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private static String itemIdOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static String itemNameOf(ItemStack stack) {
        return stack.getName().getString().trim();
    }

    private static boolean isJunk(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String name = itemNameOf(stack).toLowerCase(Locale.ROOT);
        if (JUNK_NAME_EXCEPTIONS.contains(name)) return false;
        if (JUNK_ITEM_IDS.contains(itemIdOf(stack))) return true;
        if (JUNK_EXACT_NAMES.contains(name)) return true;
        String id = itemIdOf(stack);
        for (Pattern pattern : JUNK_ID_PATTERNS) {
            if (pattern.matcher(id).find()) return true;
        }
        for (Pattern pattern : JUNK_NAME_PATTERNS) {
            if (pattern.matcher(name).find()) return true;
        }
        return false;
    }

    /** Everything that would be dropped right now: junk stacks whole, and the excess of the "keep at most" items. */
    private static List<DropAction> buildDropActions(ClientPlayerEntity player) {
        List<DropAction> actions = new ArrayList<>();
        Map<String, List<int[]>> limited = new HashMap<>(); // id -> list of {slot, count}
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = itemIdOf(s);
            if (KEEP_AT_MOST.containsKey(id)) {
                limited.computeIfAbsent(id, k -> new ArrayList<>()).add(new int[]{i, s.getCount()});
            } else if (isJunk(s)) {
                actions.add(new DropAction(i, true, id, itemNameOf(s)));
            }
        }
        for (Map.Entry<String, List<int[]>> e : limited.entrySet()) {
            int total = 0;
            for (int[] st : e.getValue()) total += st[1];
            int excess = total - KEEP_AT_MOST.get(e.getKey());
            if (excess <= 0) continue;
            e.getValue().sort(Comparator.<int[]>comparingInt(st -> st[1])); // smallest stacks go first
            for (int[] st : e.getValue()) {
                if (excess <= 0) break;
                String name = itemNameOf(player.getInventory().getStack(st[0]));
                if (st[1] <= excess) {
                    actions.add(new DropAction(st[0], true, e.getKey(), name));
                    excess -= st[1];
                } else {
                    for (int k = 0; k < excess; k++) actions.add(new DropAction(st[0], false, e.getKey(), name));
                    excess = 0;
                }
            }
        }
        return actions;
    }

    /** /radiantalert items: every inventory stack with its id, name and whether it would be dropped. */
    private void dumpItems(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        Set<Integer> dropSlots = new HashSet<>();
        for (DropAction a : buildDropActions(player)) dropSlots.add(a.slot());

        appendToFile("radiantalert-items.txt", "---- inventory " + new Date() + " ----");
        int stacks = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            stacks++;
            String comps = String.valueOf(s.getComponents());
            if (comps.length() > 300) comps = comps.substring(0, 300) + "...";
            appendToFile("radiantalert-items.txt", String.format("slot %2d | %s | \"%s\" | x%d | %s | %s",
                i, itemIdOf(s), itemNameOf(s), s.getCount(), dropSlots.contains(i) ? "WOULD DROP" : "keep", comps));
        }
        say(client, "Saved " + stacks + " stacks (" + freeSlots(player) + " free slots) to radiantalert-items.txt in your Minecraft folder. "
            + "Send it to me if the drop list needs fixing.");
    }

    /** /radiantalert clean (dry run): shows what would be dropped without doing anything. */
    private void showCleanPlan(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        List<DropAction> actions = buildDropActions(player);
        say(client, "Free slots: " + freeSlots(player) + " (clean-up starts at " + INVENTORY_FREE_THRESHOLD
            + " or fewer). DRY RUN - nothing is dropped.");
        if (actions.isEmpty()) {
            say(client, "Nothing in your inventory matches the drop list.");
            return;
        }
        Map<String, int[]> summary = new TreeMap<>();
        for (DropAction a : actions) {
            ItemStack s = player.getInventory().getStack(a.slot());
            String label = a.name() + " (" + a.itemId() + ")";
            summary.computeIfAbsent(label, k -> new int[]{0})[0] += a.wholeStack() ? s.getCount() : 1;
        }
        for (Map.Entry<String, int[]> e : summary.entrySet()) {
            player.sendMessage(Text.literal(" - " + e.getKey() + " x" + e.getValue()[0]), false);
        }
        say(client, "Run /radiantalert clean go to do it now, or /radiantalert autoclean to do it automatically.");
    }

    private void startClean(MinecraftClient client, boolean auto) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (task != Task.IDLE) { say(client, "Busy with something else right now."); return; }
        List<DropAction> actions = buildDropActions(player);
        if (actions.isEmpty()) {
            cleanCooldownUntil = System.currentTimeMillis() + CLEAN_COOLDOWN_MS;
            say(client, "Inventory is nearly full (" + freeSlots(player) + " free) but nothing matches the drop list - skipping.");
            return;
        }
        task = Task.CLEAN;
        cStage = CStage.SEND_DEPOSIT;
        cTicks = 0;
        cArrivedAt = -1;
        dropsDone = 0;
        dropDelay = 0;
        dropQueue.clear();
        autoLog("CLEAN start (" + (auto ? "auto" : "manual") + ") free=" + freeSlots(player) + " actions=" + actions.size());
        say(client, "Inventory nearly full (" + freeSlots(player) + " free): going to /" + DEPOSIT_COMMAND
            + " to drop " + actions.size() + " item action(s). Open chat and run /radiantalert stop to cancel.");
    }

    private void cleanTick(MinecraftClient client, ClientPlayerEntity player) {
        cTicks++;
        switch (cStage) {
            case SEND_DEPOSIT -> {
                cStartPos = player.getPos();
                player.networkHandler.sendChatCommand(DEPOSIT_COMMAND);
                autoLog("SENT /" + DEPOSIT_COMMAND);
                cStage = CStage.WAIT_ARRIVE;
                cTicks = 0;
                cArrivedAt = -1;
            }
            case WAIT_ARRIVE -> {
                // arrival = a server switch (onServerHop) or being moved more than 5 blocks
                if (cArrivedAt < 0 && player.getPos().squaredDistanceTo(cStartPos) > 25.0) {
                    cArrivedAt = cTicks;
                    autoLog("CLEAN moved to the drop-off");
                }
                boolean timedOut = cTicks > 20 * 20;
                if ((cArrivedAt >= 0 && cTicks - cArrivedAt >= 40) || timedOut) {
                    if (timedOut) say(client, "Didn't notice the teleport - dropping here.");
                    dropQueue.clear();
                    dropQueue.addAll(buildDropActions(player));
                    autoLog("CLEAN dropping " + dropQueue.size() + " action(s)");
                    cStage = CStage.DROPPING;
                    cTicks = 0;
                    dropDelay = 0;
                    dropsDone = 0;
                }
            }
            case DROPPING -> {
                if (dropDelay > 0) { dropDelay--; return; }
                DropAction a = dropQueue.poll();
                if (a == null) { finishClean(client); return; }
                if (client.interactionManager == null) return;
                ItemStack s = player.getInventory().getStack(a.slot());
                // skip anything that changed since it was planned
                if (s.isEmpty() || !itemIdOf(s).equals(a.itemId()) || !itemNameOf(s).equals(a.name())) return;
                int screenSlot = a.slot() < 9 ? 36 + a.slot() : a.slot();
                client.interactionManager.clickSlot(player.playerScreenHandler.syncId, screenSlot,
                    a.wholeStack() ? 1 : 0, SlotActionType.THROW, player);
                dropsDone++;
                dropDelay = DROP_DELAY_TICKS;
            }
        }
    }

    private void finishClean(MinecraftClient client) {
        int n = dropsDone;
        cleanCooldownUntil = System.currentTimeMillis() + CLEAN_COOLDOWN_MS;
        stopClean(client, "Dropped " + n + " item action(s).");
        if (RETURN_HOME_AFTER_CLEAN) sendHome(client);
    }

    private void stopClean(MinecraftClient client, String message) {
        task = Task.IDLE;
        dropQueue.clear();
        autoLog("CLEAN stop: " + message);
        say(client, message);
    }

    // ============================== Hoopa on the shoulder ==============================

    private PokemonEntity findOwnHoopa(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        PokemonEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (PokemonEntity pe : client.world.getEntitiesByClass(PokemonEntity.class,
                client.player.getBoundingBox().expand(12),
                x -> client.player.getUuid().equals(x.getOwnerUuid()) && !x.isRemoved()
                    && x.getPokemon().getSpecies().getName().equalsIgnoreCase(HOOPA_SPECIES))) {
            double d = pe.squaredDistanceTo(client.player);
            if (d < bestDist) { bestDist = d; best = pe; }
        }
        return best;
    }

    private void startHoopa(MinecraftClient client, boolean manual) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (task != Task.IDLE) {
            if (manual) say(client, "Busy with something else right now.");
            return;
        }
        hoopaCooldownUntil = System.currentTimeMillis() + 60000;

        List<Pokemon> party;
        try {
            party = readParty();
        } catch (Throwable t) {
            say(client, "Couldn't read your party: " + t);
            autoLog("HOOPA party read failed: " + t);
            logPartyApi();
            return;
        }
        int slot = -1;
        for (int i = 0; i < party.size(); i++) {
            Pokemon p = party.get(i);
            if (p != null && isNormalHoopa(p)) { slot = i; break; }
        }
        if (slot < 0) {
            if (manual) say(client, "No normal Hoopa in your party (run /radiantalert party to see what it found).");
            return;
        }

        String state = pokemonState(party.get(slot));
        if (state.toLowerCase(Locale.ROOT).contains("shoulder")) {
            autoLog("HOOPA already on the shoulder (state=" + state + ")");
            if (manual) say(client, "Your Hoopa is already on your shoulder.");
            return;
        }

        hSlot = slot;
        hAttempts = 0;
        hTicks = 0;
        hPrevSlot = -1;
        hGuiTicks = 0;
        hWheelClicked = false;
        hEntity = findOwnHoopa(client);
        task = Task.HOOPA;
        hStage = hEntity != null ? HStage.APPROACH : HStage.SELECT;
        autoLog("HOOPA start slot=" + (slot + 1) + " state=" + state + " alreadyOut=" + (hEntity != null));
        say(client, hEntity != null
            ? "Your Hoopa is already out - putting it on your shoulder."
            : "Sending out your Hoopa (party slot " + (slot + 1) + ").");
    }

    private void hoopaTick(MinecraftClient client, ClientPlayerEntity player) {
        hTicks++;
        switch (hStage) {
            case SELECT -> {
                // the clean way: send the same packet that pressing R sends for that party slot
                if (trySendOutPacket(hSlot)) {
                    autoLog("HOOPA sent out via packet, slot " + (hSlot + 1));
                    hStage = HStage.WAIT_OUT;
                    hTicks = 0;
                    return;
                }
                // otherwise: select the slot, then press R
                if (!trySelectPartySlot(hSlot)) {
                    autoLog("HOOPA could not send out or select the party slot");
                    logPartyApi();
                    stopHoopa(client, "Couldn't send out or select that party slot. Details are in radiantalert-auto.txt.");
                    return;
                }
                hStage = HStage.PRESS;
                hTicks = 0;
            }
            case PRESS -> {
                if (hTicks >= 5) {
                    pressSendOutKey();
                    hStage = HStage.WAIT_OUT;
                    hTicks = 0;
                }
            }
            case WAIT_OUT -> {
                hEntity = findOwnHoopa(client);
                if (hEntity != null) {
                    hStage = HStage.APPROACH;
                    hTicks = 0;
                } else if (hTicks > 100) {
                    stopHoopa(client, "Hoopa didn't come out.");
                }
            }
            case APPROACH -> {
                // wait for the send-out animation, and get within reach (about 3 blocks) of it
                if (hEntity == null || hEntity.isRemoved()) hEntity = findOwnHoopa(client);
                if (hEntity == null) { stopHoopa(client, "Lost track of Hoopa."); return; }
                boolean tooFar = player.distanceTo(hEntity) > 2.4f;
                if (tooFar && hTicks < 100) {
                    aimAt(player, hEntity);
                    client.options.forwardKey.setPressed(true);
                    client.options.sprintKey.setPressed(false);
                    keysHeld = true;
                } else if (hTicks >= 20) {
                    releaseKeys(client);
                    // use an empty hand for the right-click if there is a free hotbar slot
                    hPrevSlot = player.getInventory().selectedSlot;
                    for (int i = 0; i < 9; i++) {
                        if (player.getInventory().getStack(i).isEmpty()) {
                            player.getInventory().selectedSlot = i;
                            break;
                        }
                    }
                    hStage = HStage.SHOULDER;
                    hTicks = 0;
                }
            }
            case SHOULDER -> {
                if (hEntity == null) { stopHoopa(client, "Lost track of Hoopa."); return; }

                // Attempts 1 and 2: send the message the wheel's "shoulder" button sends (first with the
                // entity's id, then with the Pokémon's id). No crouching, right-clicking or wheel needed.
                if (hAttempts <= 1) {
                    if (hTicks == 1 && !hEntity.isRemoved()) {
                        UUID id = hAttempts == 0 ? hEntity.getUuid() : hEntity.getPokemon().getUuid();
                        boolean sent = trySendShoulderPacket(id);
                        autoLog("HOOPA shoulder packet attempt " + (hAttempts + 1) + " ("
                            + (hAttempts == 0 ? "entity" : "pokemon") + " id) sent=" + sent);
                        if (!sent) {                         // can't send it: use the wheel instead
                            hAttempts = 2;
                            hTicks = 0;
                            return;
                        }
                    }
                    if (hTicks > 1 && hEntity.isRemoved()) {
                        hoopaMountedAt = System.currentTimeMillis();
                        stopHoopa(client, "Hoopa is on your shoulder.");
                        return;
                    }
                    if (hTicks >= 16) {
                        hAttempts++;
                        hTicks = 0;
                    }
                    return;
                }

                // Attempt 3: crouch + right-click opens Cobblemon's interaction wheel; hoopaScreen() clicks "shoulder" there
                if (hTicks == 1) {
                    client.options.sneakKey.setPressed(true);
                    sneakHeld = true;
                }
                if (hTicks == 3 && !hEntity.isRemoved() && player.distanceTo(hEntity) > 2.9f) {
                    // it wandered off while we crouched: go back to it
                    client.options.sneakKey.setPressed(false);
                    sneakHeld = false;
                    hStage = HStage.APPROACH;
                    hTicks = 0;
                    return;
                }
                if (hTicks == 4 && !hEntity.isRemoved() && client.interactionManager != null) {
                    ActionResult result = client.interactionManager.interactEntity(player, hEntity, Hand.MAIN_HAND);
                    autoLog(String.format("HOOPA crouch + right-click: distance=%.1f sneaking=%s result=%s",
                        (double) player.distanceTo(hEntity), player.isSneaking(), result));
                }
                if (hTicks > 4 && hEntity.isRemoved()) {
                    hoopaMountedAt = System.currentTimeMillis();
                    stopHoopa(client, "Hoopa is on your shoulder.");
                    return;
                }
                if (hTicks >= 60) {
                    stopHoopa(client, "Couldn't put Hoopa on your shoulder. See radiantalert-auto.txt (lines starting with HOOPA and DUMP).");
                }
            }
        }
    }

    // ---- the shoulder message and Cobblemon's interaction wheel ----

    private static Class<?> interactPacketClass = null;
    private static boolean interactPacketSearched = false;

    /** Finds Cobblemon's "interact with Pokémon" packet (the one built from a UUID and a true/false) by looking inside its jar. */
    private Class<?> findInteractPacketClass() {
        if (interactPacketSearched) return interactPacketClass;
        interactPacketSearched = true;

        List<String> candidates = new ArrayList<>();
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("cobblemon");
            if (container.isPresent()) {
                for (Path root : container.get().getRootPaths()) {
                    try (Stream<Path> walk = Files.walk(root)) {
                        walk.forEach(path -> {
                            Path fileName = path.getFileName();
                            if (fileName == null) return;
                            String n = fileName.toString();
                            if (n.endsWith(".class") && n.contains("Interact") && n.contains("Packet") && !n.contains("$")) {
                                String rel = root.relativize(path).toString().replace('\\', '/');
                                candidates.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                            }
                        });
                    }
                }
            }
        } catch (Throwable t) {
            autoLog("HOOPA could not search the Cobblemon jar: " + t);
        }

        Class<?> best = null;
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name);
                StringBuilder sb = new StringBuilder("HOOPA packet candidate " + name + " constructors:");
                boolean fits = false;
                for (Constructor<?> k : c.getConstructors()) {
                    Class<?>[] types = k.getParameterTypes();
                    sb.append(' ').append(Arrays.toString(types));
                    if (types.length == 2 && types[0] == UUID.class && types[1] == boolean.class) fits = true;
                }
                autoLog(sb.toString());
                if (fits && (best == null || c.getSimpleName().contains("Pokemon"))) best = c;
            } catch (Throwable t) {
                autoLog("HOOPA could not load " + name + ": " + t);
            }
        }
        interactPacketClass = best;
        autoLog("HOOPA interact packet class: " + (best == null ? "NOT FOUND (" + candidates.size() + " candidates)" : best.getName()));
        return best;
    }

    private boolean trySendShoulderPacket(UUID pokemonId) {
        try {
            Class<?> c = findInteractPacketClass();
            if (c == null) return false;
            for (Constructor<?> k : c.getConstructors()) {
                Class<?>[] types = k.getParameterTypes();
                if (types.length == 2 && types[0] == UUID.class && types[1] == boolean.class) {
                    Object packet = k.newInstance(pokemonId, true);
                    if (packet instanceof CustomPayload payload) {
                        ClientPlayNetworking.send(payload);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            autoLog("HOOPA shoulder packet failed: " + t);
        }
        return false;
    }

    private static boolean isCobblemonInteractScreen(Screen screen) {
        String name = screen.getClass().getName();
        return name.startsWith("com.cobblemon") && name.toLowerCase(Locale.ROOT).contains("interact");
    }

    /** Runs while Cobblemon's interaction wheel is open: write down what it contains, click "shoulder", then close it. */
    private void hoopaScreen(MinecraftClient client) {
        hGuiTicks++;
        Screen screen = client.currentScreen;
        if (screen == null) { hGuiTicks = 0; return; }
        if (hGuiTicks == 1) {
            dumpObject("WHEEL", screen, 0, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), new int[]{0});
        }
        // the wheel builds its buttons a moment after opening, so keep trying for a few ticks
        if (!hWheelClicked && (hGuiTicks == 3 || hGuiTicks == 6 || hGuiTicks == 9)) {
            hWheelClicked = tryClickShoulderOption(screen);
            autoLog("HOOPA wheel: shoulder button pressed=" + hWheelClicked + " (tick " + hGuiTicks + ")");
        }
        if (hGuiTicks >= 16) {                 // still open: close it so we are not stuck behind it
            hGuiTicks = 0;
            hWheelClicked = false;
            client.setScreen(null);
        }
    }

    /** Writes an object's fields (and the Cobblemon objects inside it) to the action log, so the wheel's structure can be read. */
    private void dumpObject(String label, Object obj, int depth, Set<Object> seen, int[] lines) {
        if (obj == null || depth > 2 || lines[0] > 150 || !seen.add(obj)) return;
        autoLog("DUMP " + label + " : " + obj.getClass().getName());
        lines[0]++;
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().startsWith("net.minecraft")) break; // skip the game's own base classes
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    String text = String.valueOf(v);
                    if (text.length() > 100) text = text.substring(0, 100) + "...";
                    autoLog("DUMP   " + label + "." + f.getName() + " (" + f.getType().getSimpleName() + ") = " + text);
                    lines[0]++;
                    Iterable<?> elems = v instanceof Multimap<?, ?> mm ? mm.values()
                        : v instanceof Map<?, ?> map ? map.values()
                        : v instanceof Iterable<?> it ? it : null;
                    if (elems != null) {
                        int n = 0;
                        for (Object o : elems) {
                            if (n++ >= 12) break;
                            dumpObject(label + "." + f.getName() + "[" + (n - 1) + "]", o, depth + 1, seen, lines);
                        }
                    } else if (v != null && v.getClass().getName().startsWith("com.cobblemon")) {
                        dumpObject(label + "." + f.getName(), v, depth + 1, seen, lines);
                    }
                } catch (Throwable ignored) {
                    // skip fields we can't read
                }
            }
        }
    }

    private boolean tryClickShoulderOption(Screen screen) {
        try {
            List<Object> reachable = reachableObjects(screen, 4);
            autoLog("HOOPA wheel: " + reachable.size() + " Cobblemon objects reachable from the screen");
            for (Object o : reachable) {
                if (o == screen) continue;
                String label = optionLabel(o);
                if (!label.contains("shoulder")) continue;
                autoLog("HOOPA wheel: candidate " + o.getClass().getName() + " (" + label.trim() + ")");
                if (invokeOptionAction(o)) return true;
            }
        } catch (Throwable t) {
            autoLog("HOOPA wheel click failed: " + t);
        }
        return false;
    }

    /** The Cobblemon objects reachable from the screen through fields, lists, maps and multimaps, a few levels deep. */
    private static List<Object> reachableObjects(Object root, int maxDepth) {
        List<Object> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Deque<Object[]> queue = new ArrayDeque<>(); // {object, depth}
        queue.add(new Object[]{root, 0});
        int budget = 2000;
        while (!queue.isEmpty() && budget-- > 0) {
            Object[] item = queue.poll();
            Object obj = item[0];
            int depth = (Integer) item[1];
            if (obj == null || !seen.add(obj)) continue;

            Iterable<?> elements = null;
            if (obj instanceof Multimap<?, ?> multimap) elements = multimap.values();
            else if (obj instanceof Map<?, ?> map) elements = map.values();
            else if (obj instanceof Iterable<?> iterable) elements = iterable;
            else if (obj instanceof Object[] array) elements = Arrays.asList(array);
            if (elements != null) {
                if (depth < maxDepth) {
                    for (Object o : elements) {
                        if (o != null) queue.add(new Object[]{o, depth + 1});
                    }
                }
                continue;
            }

            if (!obj.getClass().getName().startsWith("com.cobblemon")) continue;
            result.add(obj);
            if (depth >= maxDepth) continue;
            for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                if (k.getName().startsWith("net.minecraft")) break;
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) queue.add(new Object[]{v, depth + 1});
                    } catch (Throwable ignored) {
                        // skip
                    }
                }
            }
        }
        return result;
    }

    /** All the text-like values on an option (names, ids, enum names), lowercased, to tell which one is "shoulder". */
    private static String optionLabel(Object option) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = option.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(option);
                    if (v instanceof Text text) sb.append(text.getString()).append(' ');
                    else if (v instanceof String || v instanceof Identifier || v instanceof Enum<?>) sb.append(v).append(' ');
                } catch (Throwable ignored) {
                    // skip
                }
            }
        }
        sb.append(option.getClass().getSimpleName());
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Runs the option's click action: a Runnable or a Kotlin lambda stored on it (fields named press/click/action first). */
    private boolean invokeOptionAction(Object option) {
        List<Object> actions = new ArrayList<>();
        for (Class<?> k = option.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(option);
                    if (v == null) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    boolean named = n.contains("press") || n.contains("click") || n.contains("action")
                        || n.contains("callback") || n.contains("handler");
                    if (named) actions.add(0, v); else actions.add(v);
                } catch (Throwable ignored) {
                    // skip
                }
            }
        }
        for (Object action : actions) {
            try {
                if (action instanceof Runnable runnable) {
                    runnable.run();
                    return true;
                }
                java.lang.reflect.Method invoke = action.getClass().getMethod("invoke");
                invoke.setAccessible(true);
                invoke.invoke(action);
                return true;
            } catch (NoSuchMethodException ignored) {
                // not a function: try the next one
            } catch (Throwable t) {
                autoLog("HOOPA wheel action failed: " + t);
            }
        }
        return false;
    }

    private void stopHoopa(MinecraftClient client, String message) {
        task = Task.IDLE;
        hEntity = null;
        releaseKeys(client);
        if (sneakHeld) {
            client.options.sneakKey.setPressed(false);
            sneakHeld = false;
        }
        if (hPrevSlot >= 0 && client.player != null) {
            client.player.getInventory().selectedSlot = hPrevSlot;
            hPrevSlot = -1;
        }
        autoLog("HOOPA stop: " + message);
        say(client, message);
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
        lastListenText = null;
        listenRepeats = 0;
        lastScreenName = screenName(client);
        appendToFile("radiantalert-listen.txt", "---- new listen session " + new Date() + " ----");
        listening = true;
        listenLog("START " + contextString(client));
        say(client, "Listening. Switch servers and do whatever you want recorded, then run /radiantalert listen again to stop. "
            + "Everything is saved to radiantalert-listen.txt in your Minecraft folder.");
    }

    private void listenLog(String text) {
        if (!listening) return;
        if (text.equals(lastListenText)) { listenRepeats++; return; } // collapse identical lines
        if (listenRepeats > 0) {
            appendToFile("radiantalert-listen.txt", "          (the line above repeated " + listenRepeats + " more times)");
            listenRepeats = 0;
        }
        lastListenText = text;
        if (listenLines++ > LISTEN_MAX_LINES) return;
        double secs = (System.currentTimeMillis() - listenStart) / 1000.0;
        appendToFile("radiantalert-listen.txt", String.format("[+%.2fs] %s", secs, text));
    }

    private static boolean isListenNoise(String packetId) {
        for (String prefix : LISTEN_IGNORED_PACKET_PREFIXES) {
            if (packetId.startsWith(prefix)) return true;
        }
        return false;
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
        if (LISTEN_IGNORED_ENTITIES.contains(id) || id.startsWith("xercapaint:")) return;
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
