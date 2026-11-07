package dev.flabebealert;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Test mod: alerts when a wild Flabebe spawns. With auto-catch switched on (/flabebealert auto)
 * it finds a route to the Pokemon (around walls, out of holes, up stairs - even if that means
 * walking away from it first), stands a few blocks back, throws a Poke Ball, then WAITS for the
 * result before doing anything else.
 *
 * How a throw is judged (worked out from a recorded timeline on Delta):
 *  - the Pokemon turns invisible while it is inside the ball;
 *  - caught       = the Pokemon entity is removed after it went into the ball;
 *  - broke free   = the Pokemon becomes visible again and the ball is removed;
 *  - missed       = the ball is removed and the Pokemon never went into it.
 *
 * /flabebealert listen records a timeline of what the game does, for tuning.
 */
public class FlabebeAlertClient implements ClientModInitializer {

    // ---------------- Settings ----------------
    private static final String SPECIES_PREFIX = "flab";

    /** Throwing zone, in horizontal blocks from the Pokémon. Once inside, the wider "stay" band applies so it doesn't flip-flop. */
    private static final double ZONE_MIN_ENTER = 3.0;
    private static final double ZONE_MAX_ENTER = 5.5;
    private static final double ZONE_MIN_STAY = 2.3;
    private static final double ZONE_MAX_STAY = 6.5;
    /** The route planner aims for a spot this far (horizontal blocks) from the Pokémon, with a clear view of it. */
    private static final double GOAL_MIN = 3.3;
    private static final double GOAL_MAX = 5.0;
    /** How many map squares the route planner may look at before settling for the closest it found. */
    private static final int PLAN_MAX_NODES = 15000;
    /** Minimum gap between route plans (ticks). */
    private static final int REPLAN_COOLDOWN_TICKS = 10;

    /** Give up after this many balls. */
    private static final int MAX_THROWS = 6;
    /** Give up after this long in total (ticks, 20 = 1 second). */
    private static final int MAX_AUTO_TICKS = 20 * 240;
    /** Don't chase anything further away than this (blocks). */
    private static final double MAX_APPROACH_DISTANCE = 80;
    /** Balls to use, in order. Must be in your hotbar. Add cobblemon:master_ball etc. if you want them used. */
    private static final List<String> BALL_PRIORITY = List.of(
        "cobblemon:poke_ball", "cobblemon:great_ball", "cobblemon:ultra_ball");

    // How the result is worked out after a throw
    /** How long to wait for the ball to show up in the world after throwing. */
    private static final int BALL_APPEAR_TIMEOUT_TICKS = 40;
    /** If the ball can't be seen at all, fall back to waiting this long before the next throw. */
    private static final int FALLBACK_WAIT_TICKS = 100;
    /** Pokémon visible again this long (and ball gone) = it broke free. */
    private static final int ESCAPE_CONFIRM_TICKS = 3;
    /** Ball gone this long and the Pokémon never went into it = the throw missed. */
    private static final int MISS_CONFIRM_TICKS = 10;
    /** Never wait longer than this for a result. */
    private static final int MAX_RESULT_TICKS = 20 * 30;
    /** Pause after it breaks free, before throwing again. */
    private static final int ESCAPE_DELAY_TICKS = 20;
    /** Pause after a throw that missed or did nothing, before throwing again. */
    private static final int FAILED_THROW_DELAY_TICKS = 40;
    // ------------------------------------------

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;
    private static final int LISTEN_MAX_LINES = 6000;

    private enum Phase { READY, WAIT_BALL, WAIT_RESULT }

    private record Pending(PokemonEntity entity, int[] ticks) {}

    private final List<Pending> pending = new ArrayList<>();
    private final Set<UUID> alerted = Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > MAX_REMEMBERED;
        }
    });

    // auto-catch state
    private boolean autoEnabled = false;
    private PokemonEntity target = null;
    private String targetName = "";
    private int autoTicks, throwsDone, throwCooldown, switchWait;
    private float startHealth;
    private boolean keysHeld = false;

    // route following
    private List<BlockPos> path = List.of();
    private int pathIndex = 0;
    private Vec3d planTargetPos = null;
    private int replanCooldown = 0, failedPlans = 0, zoneOverrideTicks = 0, stuckTicks = 0, stuckStrikes = 0;
    private double stuckX, stuckY, stuckZ;
    private boolean zoneActive = false;
    private final Set<Long> blockedNodes = new HashSet<>();

    // throw-result tracking
    private Phase phase = Phase.READY;
    private int phaseTicks, visibleTicks, ballGoneTicks, goneTicks;
    private Entity ball = null;
    private boolean captureStarted = false;

    // listener
    private boolean listening = false;
    private long listenStart = 0;
    private int listenLines = 0;
    private int listenTick = 0;

    @Override
    public void onInitializeClient() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe && !alerted.contains(pe.getUuid())) {
                pending.add(new Pending(pe, new int[]{0}));
            }
            onEntityLoad(entity);
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> onEntityUnload(entity));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) listenLog("MESSAGE " + message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
            listenLog("CHAT " + message.getString()));

        ClientTickEvents.START_CLIENT_TICK.register(this::autoTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("flabebealert")
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debugNearest(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("auto").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoEnabled = !autoEnabled;
                    say(c, autoEnabled
                        ? "Auto-catch ON. I'll walk to the next Flabébé that spawns and throw balls at it. Cancel with /flabebealert stop."
                        : "Auto-catch OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    autoEnabled = false;
                    if (target != null) stopAuto(c, "Stopped.");
                    else say(c, "Auto-catch is OFF.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("catch").executes(ctx -> {
                    catchNearest(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("listen").executes(ctx -> {
                    toggleListen(MinecraftClient.getInstance());
                    return 1;
                }))));
    }

    // ------------------------- Detection -------------------------

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { pending.clear(); return; }

        if (listening && ++listenTick % 5 == 0) snapshot(client);

        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            PokemonEntity e = p.entity();

            if (e.isRemoved() || ++p.ticks()[0] > MAX_WAIT_TICKS) { it.remove(); continue; }
            if (e.getOwnerUuid() != null) { it.remove(); continue; }

            Pokemon pokemon = e.getPokemon();
            if (pokemon.getSpecies().getName().toLowerCase(Locale.ROOT).startsWith(SPECIES_PREFIX)) {
                it.remove();
                if (alerted.add(e.getUuid())) {
                    announce(client, e);
                    if (autoEnabled && target == null) startAuto(client, e);
                }
            }
        }
    }

    private void announce(MinecraftClient client, PokemonEntity e) {
        long dist = Math.round(client.player.distanceTo(e));
        MutableText msg = Text.literal("[TEST] Flabébé spawned! ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
            .append(Text.literal(String.format("at %d, %d, %d (%d blocks away)",
                    e.getBlockX(), e.getBlockY(), e.getBlockZ(), dist)).formatted(Formatting.GRAY));
        client.player.sendMessage(msg, false);
        client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }

    // ------------------------- Entity events (ball tracking + listener) -------------------------

    private static String entityId(Entity entity) {
        return Registries.ENTITY_TYPE.getId(entity.getType()).toString();
    }

    private static boolean isBallId(String id) {
        return id.startsWith("cobblemon:") && id.contains("ball");
    }

    private void onEntityLoad(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String id = entityId(entity);

        if (listening && entity != client.player && !id.equals("minecraft:item") && !id.equals("minecraft:experience_orb")
                && entity.squaredDistanceTo(client.player) < 48 * 48) {
            String extra = entity instanceof PokemonEntity pe ? " (" + pe.getPokemon().getSpecies().getName() + ")" : "";
            listenLog(String.format("LOAD %s%s at %.1f, %.1f, %.1f", id, extra, entity.getX(), entity.getY(), entity.getZ()));
        }

        // The ball we just threw has appeared in the world
        if (target != null && phase == Phase.WAIT_BALL && ball == null && isBallId(id)
                && entity.squaredDistanceTo(client.player) < 100) {
            ball = entity;
            phase = Phase.WAIT_RESULT;
            phaseTicks = 0;
            say(client, "Ball #" + throwsDone + " is in the air.");
        }
    }

    private void onEntityUnload(Entity entity) {
        if (!listening) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || entity == client.player) return;
        String id = entityId(entity);
        if (id.equals("minecraft:item") || id.equals("minecraft:experience_orb")) return;
        if (entity.squaredDistanceTo(client.player) > 48 * 48) return;
        String extra = entity instanceof PokemonEntity pe ? " (" + pe.getPokemon().getSpecies().getName() + ")" : "";
        listenLog(String.format("UNLOAD %s%s reason=%s at %.1f, %.1f, %.1f", id, extra,
            entity.getRemovalReason(), entity.getX(), entity.getY(), entity.getZ()));
    }

    // ------------------------- Auto-catch -------------------------

    private void startAuto(MinecraftClient client, PokemonEntity e) {
        if (client.player.distanceTo(e) > MAX_APPROACH_DISTANCE) {
            say(client, "Too far away to chase (more than " + (int) MAX_APPROACH_DISTANCE + " blocks).");
            return;
        }
        target = e;
        targetName = e.getPokemon().getSpecies().getName();
        autoTicks = 0;
        throwsDone = 0;
        throwCooldown = 0;
        switchWait = 0;
        phase = Phase.READY;
        ball = null;
        startHealth = client.player.getHealth();

        path = List.of();
        pathIndex = 0;
        planTargetPos = null;
        replanCooldown = 0;
        failedPlans = 0;
        zoneOverrideTicks = 0;
        stuckTicks = 0;
        stuckStrikes = 0;
        stuckX = client.player.getX();
        stuckY = client.player.getY();
        stuckZ = client.player.getZ();
        zoneActive = false;
        blockedNodes.clear();

        listenLog("AUTO start target=" + targetName);
        say(client, "Heading to " + targetName + ". Open chat and run /flabebealert stop to cancel.");
    }

    /** Test helper: go after the nearest wild Pokémon of ANY species within 48 blocks. */
    private void catchNearest(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        PokemonEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PokemonEntity pe : client.world.getEntitiesByClass(
                PokemonEntity.class, client.player.getBoundingBox().expand(48), x -> x.getOwnerUuid() == null)) {
            double d = pe.squaredDistanceTo(client.player);
            if (d < best) { best = d; nearest = pe; }
        }
        if (nearest == null) {
            say(client, "No wild Pokémon within 48 blocks.");
            return;
        }
        startAuto(client, nearest);
    }

    private void autoTick(MinecraftClient client) {
        if (target == null) { releaseKeys(client); return; }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { releaseKeys(client); target = null; return; }
        if (client.currentScreen != null) { releaseKeys(client); return; } // paused while chat/inventory/battle is open

        if (player.getHealth() > startHealth) startHealth = player.getHealth();
        if (player.getHealth() < startHealth || player.isOnFire()) { stopAuto(client, "Took damage - stopping."); return; }
        if (++autoTicks > MAX_AUTO_TICKS) { stopAuto(client, "Timed out."); return; }

        // The same Pokémon entity stays in the world the whole time (it just turns invisible
        // inside the ball), so we follow that one entity. Removed = gone.
        PokemonEntity current = target.isRemoved() ? null : target;

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
            followPath(client, player, current);
        }
    }

    // ------------------------- Throwing -------------------------

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
        if (throwsDone >= MAX_THROWS) {
            stopAuto(client, "Used " + MAX_THROWS + " balls and " + targetName + " is still here.");
            return;
        }
        if (switchWait > 0) { switchWait--; return; }
        if (angleError(player, current) > 4.0f) return; // wait until we're aimed at it

        int slot = findBallSlot(player);
        if (slot < 0) { stopAuto(client, "No Poké Ball in your hotbar."); return; }
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
        listenLog("THROW #" + throwsDone);
        say(client, "Threw ball #" + throwsDone + " at " + targetName + ". Waiting for the result...");
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
                listenLog("RESULT no ball entity seen, using fallback wait");
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
            listenLog("RESULT " + targetName + " went into the ball");
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

    /** It broke free, or the ball missed. Put anything you want to happen then in here. */
    private void onEscaped(MinecraftClient client, boolean brokeFree) {
        listenLog("RESULT " + (brokeFree ? "broke free" : "missed"));
        say(client, targetName + (brokeFree ? " broke free!" : " wasn't captured (the ball missed or was discarded).")
            + " Used " + throwsDone + " of " + MAX_THROWS + " balls.");
        phase = Phase.READY;
        throwCooldown = brokeFree ? ESCAPE_DELAY_TICKS : FAILED_THROW_DELAY_TICKS;
        ball = null;
        zoneActive = false; // it will probably be right next to us now: re-check the distance from scratch
    }

    /** It was caught. Put anything you want to happen then in here. */
    private void onCaught(MinecraftClient client) {
        listenLog("RESULT caught");
        stopAuto(client, "✔ Caught " + targetName + " with ball #" + throwsDone + "!");
        if (client.player != null) client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    private void stopAuto(MinecraftClient client, String message) {
        target = null;
        phase = Phase.READY;
        ball = null;
        path = List.of();
        zoneActive = false;
        releaseKeys(client);
        listenLog("AUTO stop: " + message);
        say(client, message);
    }

    private void releaseKeys(MinecraftClient client) {
        if (!keysHeld) return;
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        keysHeld = false;
    }

    private static int findBallSlot(ClientPlayerEntity player) {
        for (String wanted : BALL_PRIORITY) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = player.getInventory().getStack(i);
                if (!s.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(wanted)) return i;
            }
        }
        // fallback: any other Cobblemon ball in the hotbar (never a master ball)
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).toString();
            if (id.startsWith("cobblemon:") && id.endsWith("_ball") && !id.endsWith("master_ball")) return i;
        }
        return -1;
    }

    // ------------------------- Route following -------------------------

    private void followPath(MinecraftClient client, ClientPlayerEntity player, PokemonEntity current) {
        if (replanCooldown > 0) replanCooldown--;

        boolean noPath = path.isEmpty() || pathIndex >= path.size();
        boolean targetMoved = planTargetPos == null || current.getPos().squaredDistanceTo(planTargetPos) > 9.0;
        boolean offPath = !noPath && isOffPath(player, path.get(pathIndex));

        if (noPath || targetMoved || offPath) {
            if (replanCooldown > 0) { releaseKeys(client); return; }
            replanCooldown = REPLAN_COOLDOWN_TICKS;
            if (!replan(client, player, current)) return; // gave up
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
                if (stuckStrikes >= 6) { stopAuto(client, "Got stuck and couldn't find a way out - stopping."); return; }
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

    /** Plans a new route. Returns false if auto-catch gave up. */
    private boolean replan(MinecraftClient client, ClientPlayerEntity player, PokemonEntity current) {
        planTargetPos = current.getPos();
        Plan plan = new Pathfinder(client.world, player, current, blockedNodes).search();
        listenLog("PLAN " + plan.status() + " steps=" + plan.nodes().size());

        switch (plan.status()) {
            case AT_GOAL -> {
                // already standing in a good spot: just throw
                path = List.of();
                pathIndex = 0;
                zoneOverrideTicks = 40;
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
                    stopAuto(client, "Couldn't find a way to " + targetName + " - stopping.");
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------- Route planner (A*) -------------------------

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
     * Searches the loaded blocks for a way to a spot a few blocks from the Pokémon with a clear
     * view of it. Moves: walk, walk diagonally, step up one block (jump), drop down up to three.
     * It treats lava, fire, cactus, magma, berry bushes, cobwebs and powder snow as no-go, and it
     * doesn't count deep water as ground. If the Pokémon is too far for one search, it returns a
     * route to the closest point it found, and the next plan carries on from there.
     */
    private static final class Pathfinder {
        private final ClientWorld world;
        private final ClientPlayerEntity player;
        private final double tx, ty, tz, targetEyeY;
        private final Set<Long> blocked;
        private final Map<Long, Boolean> standCache = new HashMap<>();
        private final Map<Long, Boolean> goalCache = new HashMap<>();

        Pathfinder(ClientWorld world, ClientPlayerEntity player, PokemonEntity target, Set<Long> blocked) {
            this.world = world;
            this.player = player;
            this.tx = target.getX();
            this.ty = target.getY();
            this.tz = target.getZ();
            this.targetEyeY = target.getEyeY();
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
            return Math.max(0.0, Math.hypot(p.getX() + 0.5 - tx, p.getZ() + 0.5 - tz) - GOAL_MAX);
        }

        /** A spot a few blocks from the Pokémon, roughly level with it, with nothing in the way. */
        private boolean isGoal(BlockPos p) {
            long key = p.asLong();
            Boolean cached = goalCache.get(key);
            if (cached != null) return cached;

            double hd = Math.hypot(p.getX() + 0.5 - tx, p.getZ() + 0.5 - tz);
            boolean ok = false;
            if (hd >= GOAL_MIN && hd <= GOAL_MAX && Math.abs(p.getY() - ty) <= 3.0) {
                Vec3d from = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                Vec3d to = new Vec3d(tx, targetEyeY, tz);
                ok = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, player)).getType() == HitResult.Type.MISS;
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

    // ------------------------- Aiming -------------------------

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

    // ------------------------- Listener -------------------------

    private void toggleListen(MinecraftClient client) {
        if (client.player == null) return;
        if (listening) {
            listening = false;
            say(client, "Listener stopped. Results are in flabebealert-listen.txt in your Minecraft folder.");
            return;
        }
        listening = true;
        listenStart = System.currentTimeMillis();
        listenLines = 0;
        listenTick = 0;
        writeListen("---- new listen session " + new Date() + " ----");
        say(client, "Listening. Throw a ball at a Pokémon (or start auto-catch) and let it finish, then run /flabebealert listen again. "
            + "Everything is saved to flabebealert-listen.txt in your Minecraft folder.");
    }

    private void listenLog(String text) {
        if (!listening) return;
        if (listenLines++ > LISTEN_MAX_LINES) return;
        double secs = (System.currentTimeMillis() - listenStart) / 1000.0;
        writeListen(String.format("[+%.2fs] %s", secs, text));
    }

    private void writeListen(String line) {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("flabebealert-listen.txt");
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // can't write the log - nothing more to do
        }
    }

    /** Every 5 ticks: where the balls and nearby wild Pokémon are. */
    private void snapshot(MinecraftClient client) {
        for (Entity e : client.world.getEntities()) {
            if (e.squaredDistanceTo(client.player) > 16 * 16) continue;
            String id = entityId(e);
            if (isBallId(id)) {
                listenLog(String.format("BALL %s pos=%.1f, %.1f, %.1f", id, e.getX(), e.getY(), e.getZ()));
            } else if (e instanceof PokemonEntity pe && pe.getOwnerUuid() == null) {
                listenLog(String.format("POKEMON %s pos=%.1f, %.1f, %.1f invisible=%s",
                    pe.getPokemon().getSpecies().getName(), e.getX(), e.getY(), e.getZ(), e.isInvisible()));
            }
        }
    }

    // ------------------------- Misc -------------------------

    private static void say(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[FlabebeAlert] " + text).formatted(Formatting.GRAY), false);
        }
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
            say(client, "No Pokémon within 48 blocks.");
            return;
        }
        Pokemon p = nearest.getPokemon();
        say(client, p.getSpecies().getName() + " | shiny=" + p.getShiny() + " | aspects=" + p.getAspects());
    }
}
