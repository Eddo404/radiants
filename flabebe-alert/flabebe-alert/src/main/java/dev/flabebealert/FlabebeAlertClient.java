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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Test mod: alerts when a wild Flabebe spawns. With auto-catch switched on (/flabebealert auto)
 * it walks up to it, throws a Poke Ball, then WAITS for the result before doing anything else.
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

    /** Stop walking and start throwing once this close (blocks) and the Pokémon is in view. */
    private static final double THROW_RANGE = 5.0;
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
    private int autoTicks, throwsDone, throwCooldown, switchWait, stuckCheck;
    private float startHealth;
    private Vec3d lastCheckPos = Vec3d.ZERO;
    private boolean keysHeld = false;

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
        stuckCheck = 0;
        phase = Phase.READY;
        ball = null;
        startHealth = client.player.getHealth();
        lastCheckPos = client.player.getPos();
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

        double dist = player.distanceTo(current);
        if (dist > MAX_APPROACH_DISTANCE) { stopAuto(client, "Too far away - stopping."); return; }

        aimAt(player, current);

        if (dist <= THROW_RANGE && player.canSee(current)) {
            releaseKeys(client);
            throwTick(client, player, current);
        } else {
            walk(client, player);
        }
    }

    private void walk(MinecraftClient client, ClientPlayerEntity player) {
        if (!isSafeAhead(client, player)) { stopAuto(client, "Cliff or lava ahead - stopped."); return; }
        if (++stuckCheck >= 40) {
            stuckCheck = 0;
            if (player.getPos().squaredDistanceTo(lastCheckPos) < 1.0) { stopAuto(client, "Got stuck - stopping."); return; }
            lastCheckPos = player.getPos();
        }
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(true);
        client.options.jumpKey.setPressed(player.horizontalCollision || player.isTouchingWater());
        keysHeld = true;
    }

    private void throwTick(MinecraftClient client, ClientPlayerEntity player, PokemonEntity current) {
        stuckCheck = 0;
        lastCheckPos = player.getPos();

        if (throwCooldown > 0) { throwCooldown--; return; }
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

    /** False if the spot just ahead has lava or no ground within 4 blocks below. */
    private static boolean isSafeAhead(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d dir = Vec3d.fromPolar(0, player.getYaw());
        Vec3d ahead = player.getPos().add(dir.multiply(1.5));
        BlockPos base = BlockPos.ofFloored(ahead.x, ahead.y, ahead.z);
        boolean ground = false;
        for (int k = 0; k <= 4; k++) {
            BlockPos pos = base.down(k);
            BlockState state = client.world.getBlockState(pos);
            if (state.getFluidState().isIn(FluidTags.LAVA)) return false;
            if (k >= 1 && !state.getCollisionShape(client.world, pos).isEmpty()) ground = true;
        }
        return ground;
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
