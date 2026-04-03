package com.gblfxt.llmoblings.compat;

import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Integration with Cobblemon mod for Pokemon companion support.
 */
public class CobblemonIntegration {

    private static Boolean cobblemonLoaded = null;
    private static Class<?> pokemonEntityClass = null;

    public static boolean isCobblemonLoaded() {
        if (cobblemonLoaded == null) {
            cobblemonLoaded = ModList.get().isLoaded("cobblemon");
            if (cobblemonLoaded) {
                LLMoblings.LOGGER.info("Cobblemon mod detected - Pokemon companion support enabled");
                try {
                    pokemonEntityClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
                } catch (ClassNotFoundException e) {
                    LLMoblings.LOGGER.warn("Could not load PokemonEntity class: {}", e.getMessage());
                    pokemonEntityClass = null;
                }
            }
        }
        return cobblemonLoaded;
    }

    /**
     * Check if an entity is a Pokemon.
     */
    public static boolean isPokemon(Entity entity) {
        if (!isCobblemonLoaded() || pokemonEntityClass == null) {
            return false;
        }
        return pokemonEntityClass.isInstance(entity);
    }

    /**
     * Check if a Pokemon is wild (not owned).
     */
    public static boolean isWildPokemon(Entity entity) {
        if (!isPokemon(entity)) {
            return false;
        }

        try {
            // Get the Pokemon data object
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return true;

            // Call isWild()
            Method isWildMethod = pokemon.getClass().getMethod("isWild");
            return (Boolean) isWildMethod.invoke(pokemon);
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error checking if Pokemon is wild: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Get the owner of a Pokemon.
     */
    public static LivingEntity getPokemonOwner(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return null;

            // Try getOwnerEntity()
            Method getOwnerMethod = pokemon.getClass().getMethod("getOwnerEntity");
            return (LivingEntity) getOwnerMethod.invoke(pokemon);
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon owner: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the owner UUID of a Pokemon.
     */
    public static UUID getPokemonOwnerUUID(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return null;

            Method getOwnerUUIDMethod = pokemon.getClass().getMethod("getOwnerUUID");
            return (UUID) getOwnerUUIDMethod.invoke(pokemon);
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon owner UUID: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the Pokemon data object from a PokemonEntity.
     */
    private static Object getPokemonData(Entity entity) {
        try {
            Method getPokemonMethod = entity.getClass().getMethod("getPokemon");
            return getPokemonMethod.invoke(entity);
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get Pokemon species name.
     */
    public static String getPokemonSpeciesName(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Pokémon desconhecido";

            // Get species
            Method getSpeciesMethod = pokemon.getClass().getMethod("getSpecies");
            Object species = getSpeciesMethod.invoke(pokemon);

            if (species != null) {
                Method getNameMethod = species.getClass().getMethod("getName");
                return (String) getNameMethod.invoke(species);
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon species: {}", e.getMessage());
        }

        return "Pokémon";
    }

    /**
     * Get Pokemon nickname (or species name if no nickname).
     */
    public static String getPokemonDisplayName(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return getPokemonSpeciesName(entity);

            // Try to get nickname
            Method getNicknameMethod = pokemon.getClass().getMethod("getNickname");
            Object nickname = getNicknameMethod.invoke(pokemon);

            if (nickname != null) {
                Method getStringMethod = nickname.getClass().getMethod("getString");
                String nicknameStr = (String) getStringMethod.invoke(nickname);
                if (nicknameStr != null && !nicknameStr.isEmpty()) {
                    return nicknameStr;
                }
            }
        } catch (Exception e) {
            // Fall through to species name
        }

        return getPokemonSpeciesName(entity);
    }

    /**
     * Get Pokemon level.
     */
    public static int getPokemonLevel(Entity entity) {
        if (!isPokemon(entity)) {
            return 0;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return 0;

            Method getLevelMethod = pokemon.getClass().getMethod("getLevel");
            return (Integer) getLevelMethod.invoke(pokemon);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Check if Pokemon is shiny.
     */
    public static boolean isPokemonShiny(Entity entity) {
        if (!isPokemon(entity)) {
            return false;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return false;

            Method getShinyMethod = pokemon.getClass().getMethod("getShiny");
            return (Boolean) getShinyMethod.invoke(pokemon);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Find nearby Pokemon owned by a specific player.
     */
    public static List<Entity> findPlayerPokemon(CompanionEntity companion, ServerPlayer owner, int radius) {
        if (!isCobblemonLoaded()) {
            return List.of();
        }

        AABB searchBox = companion.getBoundingBox().inflate(radius);
        UUID ownerUUID = owner.getUUID();

        return companion.level().getEntities(companion, searchBox, entity -> {
            if (!isPokemon(entity)) return false;
            if (isWildPokemon(entity)) return false;

            UUID pokemonOwnerUUID = getPokemonOwnerUUID(entity);
            return ownerUUID.equals(pokemonOwnerUUID);
        });
    }

    /**
     * Find the nearest Pokemon owned by the companion's owner.
     */
    public static Entity findNearestPlayerPokemon(CompanionEntity companion, int radius) {
        if (companion.getOwner() == null) return null;
        if (!(companion.getOwner() instanceof ServerPlayer player)) return null;

        List<Entity> playerPokemon = findPlayerPokemon(companion, player, radius);

        return playerPokemon.stream()
                .min((a, b) -> Double.compare(
                        companion.distanceTo(a),
                        companion.distanceTo(b)))
                .orElse(null);
    }

    /**
     * Make a Pokemon follow a target entity (basic navigation).
     */
    public static void makePokemonFollow(Entity pokemon, LivingEntity target) {
        if (!isPokemon(pokemon)) return;

        try {
            // Use navigation to move toward target
            if (pokemon instanceof net.minecraft.world.entity.Mob mob) {
                double speed = 1.0;
                mob.getNavigation().moveTo(target, speed);
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error making Pokemon follow: {}", e.getMessage());
        }
    }

    /**
     * Get a summary of a Pokemon for chat responses.
     */
    public static String getPokemonSummary(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        String name = getPokemonDisplayName(entity);
        String species = getPokemonSpeciesName(entity);
        int level = getPokemonLevel(entity);
        boolean shiny = isPokemonShiny(entity);

        StringBuilder sb = new StringBuilder();
        if (!name.equals(species)) {
            sb.append(name).append(" o ");
        }
        if (shiny) {
            sb.append("shiny ");
        }
        sb.append(species);
        sb.append(" (Lv. ").append(level).append(")");

        return sb.toString();
    }

    // ============== POKEMON STATS (IVs, EVs, Nature, Ability) ==============

    /**
     * Get Pokemon's nature name.
     */
    public static String getPokemonNature(Entity entity) {
        if (!isPokemon(entity)) return null;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Desconhecido";

            Method getNatureMethod = pokemon.getClass().getMethod("getNature");
            Object nature = getNatureMethod.invoke(pokemon);

            if (nature != null) {
                Method getNameMethod = nature.getClass().getMethod("getName");
                Object nameComponent = getNameMethod.invoke(nature);
                if (nameComponent != null) {
                    Method getStringMethod = nameComponent.getClass().getMethod("getString");
                    return (String) getStringMethod.invoke(nameComponent);
                }
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon nature: {}", e.getMessage());
        }
        return "Desconhecido";
    }

    /**
     * Get Pokemon's ability name.
     */
    public static String getPokemonAbility(Entity entity) {
        if (!isPokemon(entity)) return null;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Desconhecido";

            Method getAbilityMethod = pokemon.getClass().getMethod("getAbility");
            Object ability = getAbilityMethod.invoke(pokemon);

            if (ability != null) {
                Method getNameMethod = ability.getClass().getMethod("getName");
                return (String) getNameMethod.invoke(ability);
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon ability: {}", e.getMessage());
        }
        return "Desconhecido";
    }

    /**
     * Get Pokemon IVs as a formatted string.
     */
    public static String getPokemonIVs(Entity entity) {
        if (!isPokemon(entity)) return null;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Desconhecido";

            Method getIVsMethod = pokemon.getClass().getMethod("getIvs");
            Object ivs = getIVsMethod.invoke(pokemon);

            if (ivs != null) {
                // IVs object has methods like getHP(), getAttack(), etc.
                int hp = getStatValue(ivs, "getHP", "hp");
                int atk = getStatValue(ivs, "getAttack", "attack");
                int def = getStatValue(ivs, "getDefence", "defence");
                int spa = getStatValue(ivs, "getSpecialAttack", "special_attack");
                int spd = getStatValue(ivs, "getSpecialDefence", "special_defence");
                int spe = getStatValue(ivs, "getSpeed", "speed");

                int total = hp + atk + def + spa + spd + spe;
                return String.format("HP:%d Atq:%d Def:%d AtqEsp:%d DefEsp:%d Vel:%d (Total: %d/186)",
                        hp, atk, def, spa, spd, spe, total);
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon IVs: {}", e.getMessage());
        }
        return "Desconhecido";
    }

    /**
     * Get Pokemon EVs as a formatted string.
     */
    public static String getPokemonEVs(Entity entity) {
        if (!isPokemon(entity)) return null;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Desconhecido";

            Method getEVsMethod = pokemon.getClass().getMethod("getEvs");
            Object evs = getEVsMethod.invoke(pokemon);

            if (evs != null) {
                int hp = getStatValue(evs, "getHP", "hp");
                int atk = getStatValue(evs, "getAttack", "attack");
                int def = getStatValue(evs, "getDefence", "defence");
                int spa = getStatValue(evs, "getSpecialAttack", "special_attack");
                int spd = getStatValue(evs, "getSpecialDefence", "special_defence");
                int spe = getStatValue(evs, "getSpeed", "speed");

                int total = hp + atk + def + spa + spd + spe;
                return String.format("HP:%d Atq:%d Def:%d AtqEsp:%d DefEsp:%d Vel:%d (Total: %d/510)",
                        hp, atk, def, spa, spd, spe, total);
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon EVs: {}", e.getMessage());
        }
        return "Desconhecido";
    }

    /**
     * Helper to get a stat value from IVs/EVs object.
     */
    private static int getStatValue(Object statsObj, String methodName, String altMethod) {
        try {
            Method method = statsObj.getClass().getMethod(methodName);
            return (Integer) method.invoke(statsObj);
        } catch (Exception e) {
            try {
                // Try alternative method name
                Method method = statsObj.getClass().getMethod("get", String.class);
                Object result = method.invoke(statsObj, altMethod);
                if (result instanceof Integer) return (Integer) result;
            } catch (Exception e2) {
                // Try yet another pattern - some versions use getStat()
                try {
                    Method method = statsObj.getClass().getMethod("getOrDefault");
                    return 0;
                } catch (Exception e3) {
                    // Give up
                }
            }
        }
        return 0;
    }

    /**
     * Get Pokemon's current HP and max HP.
     */
    public static String getPokemonHP(Entity entity) {
        if (!isPokemon(entity)) return null;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Desconhecido";

            Method getCurrentHealthMethod = pokemon.getClass().getMethod("getCurrentHealth");
            int currentHP = (Integer) getCurrentHealthMethod.invoke(pokemon);

            // Get max HP from stats
            Method getHpMethod = pokemon.getClass().getMethod("getHp");
            int maxHP = (Integer) getHpMethod.invoke(pokemon);

            return currentHP + "/" + maxHP;
        } catch (Exception e) {
            // Fallback to entity health
            if (entity instanceof LivingEntity living) {
                return (int) living.getHealth() + "/" + (int) living.getMaxHealth();
            }
        }
        return "Desconhecido";
    }

    /**
     * Get Pokemon's friendship/happiness level.
     */
    public static int getPokemonFriendship(Entity entity) {
        if (!isPokemon(entity)) return 0;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return 0;

            Method getFriendshipMethod = pokemon.getClass().getMethod("getFriendship");
            return (Integer) getFriendshipMethod.invoke(pokemon);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get Pokemon's held item name.
     */
    public static String getPokemonHeldItem(Entity entity) {
        if (!isPokemon(entity)) return null;

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Nenhum";

            Method getHeldItemMethod = pokemon.getClass().getMethod("heldItem");
            Object heldItem = getHeldItemMethod.invoke(pokemon);

            if (heldItem != null) {
                // It's an ItemStack
                Method isEmptyMethod = heldItem.getClass().getMethod("isEmpty");
                if ((Boolean) isEmptyMethod.invoke(heldItem)) {
                    return "Nenhum";
                }

                Method getHoverNameMethod = heldItem.getClass().getMethod("getHoverName");
                Object name = getHoverNameMethod.invoke(heldItem);
                if (name != null) {
                    Method getStringMethod = name.getClass().getMethod("getString");
                    return (String) getStringMethod.invoke(name);
                }
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Error getting Pokemon held item: {}", e.getMessage());
        }
        return "Nenhum";
    }

    /**
     * Get full Pokemon stats summary for cobblestats command.
     */
    public static String getFullPokemonStats(Entity entity) {
        if (!isPokemon(entity)) return null;

        StringBuilder sb = new StringBuilder();

        String name = getPokemonDisplayName(entity);
        String species = getPokemonSpeciesName(entity);
        int level = getPokemonLevel(entity);
        boolean shiny = isPokemonShiny(entity);

        // Header
        sb.append("=== ");
        if (!name.equals(species)) {
            sb.append(name).append(" o ");
        }
        if (shiny) sb.append("*Shiny* ");
        sb.append(species).append(" ===\n");

        sb.append("Nível: ").append(level).append("\n");
        sb.append("HP: ").append(getPokemonHP(entity)).append("\n");
        sb.append("Natureza: ").append(getPokemonNature(entity)).append("\n");
        sb.append("Habilidade: ").append(getPokemonAbility(entity)).append("\n");
        sb.append("Amizade: ").append(getPokemonFriendship(entity)).append("/255\n");
        sb.append("Item segurado: ").append(getPokemonHeldItem(entity)).append("\n");
        sb.append("IVs: ").append(getPokemonIVs(entity)).append("\n");
        sb.append("EVs: ").append(getPokemonEVs(entity));

        return sb.toString();
    }

    /**
     * Get brief Pokemon stats for quick display.
     */
    public static String getBriefPokemonStats(Entity entity) {
        if (!isPokemon(entity)) return null;

        String name = getPokemonDisplayName(entity);
        int level = getPokemonLevel(entity);
        String nature = getPokemonNature(entity);
        String ability = getPokemonAbility(entity);
        String ivs = getPokemonIVs(entity);

        return String.format("%s (Nv.%d) - %s, %s | IVs: %s",
                name, level, nature, ability, ivs);
    }
}
