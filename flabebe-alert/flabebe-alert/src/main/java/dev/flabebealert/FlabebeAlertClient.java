package dev.flabebealert;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/** Test mod: alerts when a wild Flabebe spawns, to prove Pokemon detection works. */
public class FlabebeAlertClient implements ClientModInitializer {

    private static final String SPECIES_PREFIX = "flab";
    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_REMEMBERED = 500;

    private record Pending(PokemonEntity entity, int[] ticks) {}

    private final List<Pending> pending = new ArrayList<>();
    private final Set<UUID> alerted = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> e) { return size() > MAX_REMEMBERED; }
    });

    @Override
    public void onInitializeClient() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe && !alerted.contains(pe.getUuid())) {
                pending.add(new Pending(pe, new int[]{0}));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("flabebealert")
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debugNearest(MinecraftClient.getInstance());
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
            if (e.getOwnerUuid() != null) { it.remove(); continue; }

            Pokemon pokemon = e.getPokemon();
            if (pokemon.getSpecies().getName().toLowerCase(Locale.ROOT).startsWith(SPECIES_PREFIX)) {
                it.remove();
                if (alerted.add(e.getUuid())) announce(client, e);
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
            client.player.sendMessage(Text.literal("[FlabebeAlert] No Pokémon within 48 blocks."), false);
            return;
        }
        Pokemon p = nearest.getPokemon();
        client.player.sendMessage(Text.literal("[FlabebeAlert] " + p.getSpecies().getName()
            + " | shiny=" + p.getShiny() + " | aspects=" + p.getAspects()), false);
    }
}
