package dev.farmassist;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.TreeMap;

public final class FarmAssistClient implements ClientModInitializer {
    private final FarmConfig config = new FarmConfig();
    private final FarmMacro macro = new FarmMacro(config);

    private KeyMapping toggleMacroKey;
    private KeyMapping toggleHudKey;
    private KeyMapping openMenuKey;
    private boolean openMenuNext = false;
    private boolean hudEnabled = true;
    private long ticks = 0;

    @Override
    public void onInitializeClient() {
        config.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("farmassist", "main"));
        toggleMacroKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.farmassist.toggle_macro", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        toggleHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.farmassist.toggle_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));

        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.farmassist.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) macro.onChat(message.getString());
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private void onTick(Minecraft mc) {
        ticks++;

        while (toggleMacroKey.consumeClick()) {
            toggleMacro(mc);
        }
        while (toggleHudKey.consumeClick()) {
            hudEnabled = !hudEnabled;
            msg(mc, "§eHUD " + (hudEnabled ? "ON" : "OFF"), true);
        }

        while (openMenuKey.consumeClick()) {
            openMenuNext = true;
        }
        if (openMenuNext && mc.screen == null && mc.player != null) {
            openMenuNext = false;
            mc.setScreen(new FarmMenuScreen(config, macro));
        }

        macro.tick(mc);

        if (hudEnabled && mc.player != null && ticks % 10 == 0) {
            overlay(mc, Component.literal(
                    "§6FarmAssist §7| §f" + macro.state()
                            + " §7| §f" + formatTime(macro.elapsedSeconds())
                            + " §7| §fPests: " + macro.pestsKilled
                            + " §7| §fTab: " + macro.tabAlive));
        }
    }

    private void toggleMacro(Minecraft mc) {
        if (macro.isRunning()) {
            macro.stop(mc);
            msg(mc, "§cMacro arrêtée", true);
        } else {
            macro.start(mc);
            msg(mc, "§aMacro démarrée", true);
        }
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> d) {
        d.register(lit("farmassist")
                .executes(c -> {
                    openMenuNext = true; // s'ouvre au tick suivant, une fois le chat fermé
                    return 1;
                })
                .then(lit("menu").executes(c -> {
                    openMenuNext = true;
                    return 1;
                }))
                .then(lit("start").executes(c -> {
                    macro.start(Minecraft.getInstance());
                    c.getSource().sendFeedback(Component.literal("§aMacro démarrée"));
                    return 1;
                }))
                .then(lit("stop").executes(c -> {
                    macro.stop(Minecraft.getInstance());
                    c.getSource().sendFeedback(Component.literal("§cMacro arrêtée"));
                    return 1;
                }))
                .then(lit("status").executes(c -> {
                    c.getSource().sendFeedback(Component.literal("§6État: §f" + macro.state() + " §7| pests: " + macro.pestsKilled));
                    for (String k : FarmConfig.keys()) {
                        c.getSource().sendFeedback(Component.literal("§7" + k + " = §f" + config.get(k)));
                    }
                    return 1;
                }))
                .then(lit("plotinfo").executes(c -> {
                    // Affiche le plot calculé à ta position, pour vérifier que la grille correspond au vrai plot
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    double[] b = macro.plotCell(mc.player.getX(), mc.player.getZ());
                    c.getSource().sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                            "§6Plot calculé : §fX %.0f à %.0f §7| §fZ %.0f à %.0f §7(taille %.0f, marge %.0f, limite: %s)",
                            b[0], b[1], b[2], b[3], config.plotSize, config.plotMargin, config.plotLimit)));
                    c.getSource().sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                            "§7Tu es en x=%.1f z=%.1f. Va au bord d'un plot (F3) : les coordonnées doivent correspondre. "
                                    + "Sinon, place-toi au centre d'un plot et tape /farmassist plotcenter.",
                            mc.player.getX(), mc.player.getZ())));
                    return 1;
                }))
                .then(lit("plotcenter").executes(c -> {
                    // Cale la grille : le plot où tu te trouves est considéré centré sur ta position
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    double h = config.plotSize / 2.0;
                    double x = mc.player.getX() - h;
                    double z = mc.player.getZ() - h;
                    config.plotOffsetX = x - Math.floor(x / config.plotSize) * config.plotSize;
                    config.plotOffsetZ = z - Math.floor(z / config.plotSize) * config.plotSize;
                    config.save();
                    c.getSource().sendFeedback(Component.literal(String.format(java.util.Locale.ROOT,
                            "§aGrille calée : plotOffsetX=%.2f plotOffsetZ=%.2f (ce plot est centré sur ta position)",
                            config.plotOffsetX, config.plotOffsetZ)));
                    return 1;
                }))
                .then(lit("setyaw").executes(c -> {
                    // Fixe l'angle de farm sur l'orientation actuelle du joueur
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    config.farmYaw = mc.player.getYRot();
                    config.farmPitch = mc.player.getXRot();
                    config.saveActiveToProfile(); // range l'angle dans le profil de la culture active
                    config.save();
                    c.getSource().sendFeedback(Component.literal(String.format(
                            "§aAngle de farm enregistré: yaw=%.1f pitch=%.1f", config.farmYaw, config.farmPitch)));
                    return 1;
                }))
                .then(lit("tab").executes(c -> {
                    // Affiche les lignes du tab pour vérifier que le nombre de pests est bien lu
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.getConnection() == null) return 0;
                    int shown = 0;
                    for (var info : mc.getConnection().getOnlinePlayers()) {
                        Component name = info.getTabListDisplayName();
                        if (name == null) continue;
                        String text = name.getString().trim();
                        if (text.isEmpty()) continue;
                        c.getSource().sendFeedback(Component.literal("§7[tab] §f" + text));
                        if (++shown >= 60) break;
                    }
                    c.getSource().sendFeedback(Component.literal("§6Pests lus : §f" + macro.readPestsAlive(mc)));
                    c.getSource().sendFeedback(Component.literal("§6Plots lus : §f" + macro.readPestPlots(mc)));
                    return 1;
                }))
                .then(lit("trail").executes(c -> {
                    // Diagnostic : fais un clic gauche avec l'aspirateur, puis tape cette commande pour voir quelles particules le serveur envoie
                    c.getSource().sendFeedback(Component.literal("§6Particules reçues depuis la dernière fois : §f" + TrailTracker.summary(0)));
                    c.getSource().sendFeedback(Component.literal("§7Clique gauche avec l'aspirateur, retape /farmassist trail : la traînée est le type le plus fréquent. "
                            + "Mets-le dans /farmassist set trailParticle <id> si la détection auto se trompe."));
                    TrailTracker.begin(Integer.MAX_VALUE);
                    return 1;
                }))
                .then(lit("pests").executes(c -> {
                    // Diagnostic : pourquoi la chasse aux pests part (ou ne part pas)
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    int tabAlive = macro.readPestsAlive(mc);
                    int nearby = macro.countNearbyPests(mc);
                    boolean points = config.start() != null && config.end() != null;
                    c.getSource().sendFeedback(Component.literal("§6Macro: §f" + macro.state()
                            + " §7| chasse activée: §f" + config.pestsEnabled));
                    c.getSource().sendFeedback(Component.literal("§6Tab: §fpests lus = " + tabAlive
                            + " §7| seuil tab = §f" + config.pestTabThreshold
                            + " §7| lecture du tab activée: §f" + config.pestTabEnabled));
                    c.getSource().sendFeedback(Component.literal("§6Autour (rayon " + (int) config.pestScanRadius
                            + "): §f" + nearby + " pest(s) détecté(s) §7| seuil (ancien mode) = §f" + config.pestThreshold));
                    c.getSource().sendFeedback(Component.literal("§6Points début/fin définis: §f" + points
                            + " §7| chasse en attente de la fin du passage: §f" + macro.isPestsPending()));
                    if (!macro.lastHuntInfo.isEmpty()) {
                        c.getSource().sendFeedback(Component.literal("§6Dernière chasse: §f" + macro.lastHuntInfo));
                    }
                    if (tabAlive < 0) {
                        c.getSource().sendFeedback(Component.literal("§eLe tab n'est pas lu (-1) : fais /farmassist tab et corrige pestTabRegex."));
                    } else if (tabAlive < config.pestTabThreshold) {
                        c.getSource().sendFeedback(Component.literal("§eIl y a " + tabAlive + " pests mais le seuil tab est " + config.pestTabThreshold
                                + " : baisse-le avec /farmassist set pestTabThreshold " + 4));
                    } else if (points) {
                        c.getSource().sendFeedback(Component.literal("§aSeuil atteint : la chasse partira à l'arrivée au point de fin."));
                    } else {
                        c.getSource().sendFeedback(Component.literal("§aSeuil atteint : la chasse part tout de suite (pas de points début/fin)."));
                    }
                    return 1;
                }))
                .then(lit("setstart").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    config.setStart(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
                    config.save();
                    c.getSource().sendFeedback(Component.literal("§aPoint de début enregistré"));
                    return 1;
                }))
                .then(lit("setend").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return 0;
                    config.setEnd(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                    config.save();
                    c.getSource().sendFeedback(Component.literal("§aPoint de fin enregistré"));
                    return 1;
                }))
                .then(lit("clearpoints").executes(c -> {
                    config.clearPoints();
                    config.save();
                    c.getSource().sendFeedback(Component.literal("§eDébut et fin effacés"));
                    return 1;
                }))
                .then(lit("scan").executes(c -> {
                    // Liste les entités dans le rayon de détection (pestScanRadius, ex: tout le plot) pour trouver les ids à mettre dans pestTypes
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null || mc.level == null) return 0;
                    Map<String, Integer> counts = new TreeMap<>();
                    for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(config.pestScanRadius), x -> true)) {
                        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                        String name = e.hasCustomName() ? " \"" + e.getCustomName().getString() + "\"" : "";
                        counts.merge(id + name, 1, Integer::sum);
                    }
                    if (counts.isEmpty()) {
                        c.getSource().sendFeedback(Component.literal("§7Aucune entité à moins de " + (int) config.pestScanRadius + " blocs."));
                    }
                    counts.forEach((k, v) -> c.getSource().sendFeedback(Component.literal("§f" + v + "x §7" + k)));
                    return 1;
                }))
                .then(lit("set")
                        .then(arg("key", StringArgumentType.word())
                                .then(arg("value", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            String key = StringArgumentType.getString(c, "key");
                                            String value = StringArgumentType.getString(c, "value");
                                            try {
                                                if (!config.set(key, value)) {
                                                    c.getSource().sendFeedback(Component.literal("§cClé inconnue: " + key));
                                                    return 0;
                                                }
                                            } catch (RuntimeException ex) {
                                                c.getSource().sendFeedback(Component.literal("§cValeur invalide: " + value));
                                                return 0;
                                            }
                                            config.save();
                                            c.getSource().sendFeedback(Component.literal("§a" + key + " = " + config.get(key)));
                                            return 1;
                                        })))));
    }


    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(
            String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return RequiredArgumentBuilder.<FabricClientCommandSource, T>argument(name, type);
    }

    private static void overlay(Minecraft mc, Component text) {
        mc.gui.setOverlayMessage(text, false);
    }

    private static void msg(Minecraft mc, String text, boolean actionBar) {
        if (mc.player != null) {
            overlay(mc, Component.literal(text));
        }
    }

    private static String formatTime(long s) {
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
