package dev.flabebealert;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

import java.util.*;

/**
 * Test mod: alerts when a wild Flabebe spawns. With auto-catch switched on
 * (/flabebealert auto) it also walks up to it and throws Poke Balls at it.
 */
public class FlabebeAlertClient implements ClientModInitializer {

    // ---------------- Settings ----------------
    private static final String SPECIES_PREFIX = "flab";

    /** Stop walking and start throwing once this close (blocks) and the Pokémon is in view. */
    private static final double THROW_RANGE = 5.0;
    /** Ticks to wait between throws (20 ticks = 1 second). */
    private static final int THROW_COOLDOWN_TICKS = 80;
    /** Give up after this many balls. */
    private static final int MAX_THROWS = 6;
    /** Give up after this long in total (ticks). */
    private static final int MAX_AUTO_TICKS = 20 * 90;
    /** Don't chase anything further away than this (blocks). */
    private static final double MAX_APPROACH_DISTANCE = 80;
    /** Balls to use, in order. Must be in your hotbar. Add cobblemon:master_ball etc. if you want them used. */
    private static final List<String> BALL_PRIORITY = List.of(
        "cobblemon:poke_ball", "cobblemon:great_ball", "cobblemon:ultra_ball");
    // ------------------------------------------

    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;

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

    @Override
    public void onInitializeClient() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe && !alerted.contains(pe.getUuid())) {
                pending.add(new Pending(pe, new int[]{0}));
            }
        });

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
                }))));
    }

    // ------------------------- Detection -------------------------

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { pending.clear(); return; }

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
        startHealth = client.player.getHealth();
        lastCheckPos = client.player.getPos();
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

        if (target.isRemoved()) {
            stopAuto(client, throwsDone > 0
                ? targetName + " is gone after " + throwsDone + " ball(s) - probably caught!"
                : targetName + " disappeared before I could throw.");
            return;
        }
        if (player.getHealth() > startHealth) startHealth = player.getHealth();
        if (player.getHealth() < startHealth || player.isOnFire()) { stopAuto(client, "Took damage - stopping."); return; }
        if (++autoTicks > MAX_AUTO_TICKS) { stopAuto(client, "Timed out."); return; }

        double dist = player.distanceTo(target);
        if (dist > MAX_APPROACH_DISTANCE) { stopAuto(client, "Too far away - stopping."); return; }

        aimAt(player, target);

        if (dist <= THROW_RANGE && player.canSee(target)) {
            releaseKeys(client);
            throwTick(client, player);
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

    private void throwTick(MinecraftClient client, ClientPlayerEntity player) {
        stuckCheck = 0;
        lastCheckPos = player.getPos();

        if (throwCooldown > 0) { throwCooldown--; return; }
        if (throwsDone >= MAX_THROWS) {
            stopAuto(client, "Used " + MAX_THROWS + " balls and " + targetName + " is still here.");
            return;
        }
        if (switchWait > 0) { switchWait--; return; }
        if (angleError(player, target) > 4.0f) return; // wait until we're aimed at it

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
        throwCooldown = THROW_COOLDOWN_TICKS;
        say(client, "Threw ball #" + throwsDone + " at " + targetName + ".");
    }

    private void stopAuto(MinecraftClient client, String message) {
        target = null;
        releaseKeys(client);
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
