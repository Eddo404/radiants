package dev.radiantalert;

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

public class RadiantAlertClient implements ClientModInitializer {

    // ---- Settings: change these to taste ----
    private static final boolean ALERT_RADIANT = true;
    private static final boolean ALERT_SHINY = true;
    private static final boolean ALERT_SPECIES = true;

    /** Aspect names that mark a Pokémon as Radiant. Use /radiantalert debug to check what Delta uses. */
    private static final Set<String> RADIANT_ASPECTS = Set.of("radiant");

    /** Species to alert on (lowercase). */
    private static final Set<String> WATCHED_SPECIES = Set.of("lunala", "solgaleo");
    // -----------------------------------------

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
            dispatcher.register(ClientCommandManager.literal("radiantalert")
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

    private void announce(MinecraftClient client, PokemonEntity e, boolean radiant, boolean shiny, boolean species) {
        List<String> tags = new ArrayList<>();
        if (radiant) tags.add("Radiant");
        if (shiny) tags.add("Shiny");
        if (species) tags.add("Legendary");

        Formatting color = radiant ? Formatting.AQUA : shiny ? Formatting.YELLOW : Formatting.LIGHT_PURPLE;
        long dist = Math.round(client.player.distanceTo(e));

        MutableText msg = Text.literal("[ALERT] " + String.join(" + ", tags) + " spawned! ").formatted(color, Formatting.BOLD)
            .append(Text.empty().append(e.getPokemon().getSpecies().getTranslatedName()).formatted(Formatting.WHITE))
            .append(Text.literal(String.format(" at %d, %d, %d (%d blocks away)",
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
            client.player.sendMessage(Text.literal("[RadiantAlert] No Pokémon within 48 blocks."), false);
            return;
        }
        Pokemon p = nearest.getPokemon();
        client.player.sendMessage(Text.literal("[RadiantAlert] " + p.getSpecies().getName()
            + " | shiny=" + p.getShiny() + " | aspects=" + p.getAspects()
            + " | radiant=" + isRadiant(p)), false);
    }
}
