package dev.farmassist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Écran de réglages. Les champs de texte sont appliqués à la fermeture, les boutons tout de suite. */
public final class FarmMenuScreen extends Screen {
    private final FarmConfig cfg;
    private final FarmMacro macro;

    private final List<Runnable> fieldApplyActions = new ArrayList<>();

    public FarmMenuScreen(FarmConfig cfg, FarmMacro macro) {
        super(Component.literal("FarmAssist"));
        this.cfg = cfg;
        this.macro = macro;
    }

    @Override
    protected void init() {
        fieldApplyActions.clear();

        int leftX = this.width / 2 - 205;
        int rightX = this.width / 2 + 10;
        int y = 28;
        int row = 20;

        addRenderableWidget(new StringWidget(leftX, 10, 200, 14, Component.literal("§6FarmAssist - Réglages"), this.font));

        // Bouton de culture (en haut à droite) : Auto -> Blé -> Carotte -> ... Chaque culture a son angle, son outil et sa direction
        addRenderableWidget(Button.builder(Component.literal("Culture : " + cfg.cropLabel()), b -> {
            applyFields();
            cfg.saveActiveToProfile();
            cfg.cycleCrop();
            cfg.save();
            Minecraft.getInstance().setScreen(new FarmMenuScreen(cfg, macro));
        }).bounds(rightX, 6, 195, 18).build());

        // --- Colonne gauche : champs de texte ---
        y = field(leftX, y, row, "Slot outil farm (1-9)", String.valueOf(cfg.farmSlot + 1),
                v -> cfg.set("farmSlot", String.valueOf(Integer.parseInt(v.trim()) - 1)));
        y = field(leftX, y, row, "Slot aspirateur (1-9)", String.valueOf(cfg.vacuumSlot + 1),
                v -> cfg.set("vacuumSlot", String.valueOf(Integer.parseInt(v.trim()) - 1)));
        y = field(leftX, y, row, "Pests (tab) avant chasse", String.valueOf(cfg.pestTabThreshold),
                v -> {
                    cfg.set("pestTabThreshold", v.trim());
                    cfg.set("pestThreshold", v.trim());
                });
        y = field(leftX, y, row, "Rayon de détection", String.valueOf(cfg.pestScanRadius),
                v -> cfg.set("pestScanRadius", v.trim()));
        y = field(leftX, y, row, "Plot de farm (n°)", cfg.farmPlot,
                v -> cfg.set("farmPlot", v.trim()));
        y = field(leftX, y, row, "Commande de TP plot", cfg.plotTpCommand,
                v -> cfg.set("plotTpCommand", v));
        y = field(leftX, y, row, "Commande retour début", cfg.startTpCommand,
                v -> cfg.set("startTpCommand", v));
        y = field(leftX, y, row, "Types de pests", cfg.pestTypes,
                v -> cfg.set("pestTypes", v));
        y = field(leftX, y, row, "Ticks avant demi-tour", String.valueOf(cfg.stuckTicks),
                v -> cfg.set("stuckTicks", v.trim()));

        // --- Colonne droite : boutons ON/OFF ---
        int by = 28;
        by = toggle(rightX, by, row, "Chasse aux pests", () -> cfg.pestsEnabled, v -> cfg.pestsEnabled = v);
        by = toggle(rightX, by, row, "Téléportation entre plots", () -> cfg.plotTeleport, v -> cfg.plotTeleport = v);
        by = toggle(rightX, by, row, "Avancer en continu", () -> cfg.holdForward, v -> cfg.holdForward = v);
        by = toggle(rightX, by, row, "Départ vers la droite", () -> cfg.startRight, v -> cfg.startRight = v);
        by = toggle(rightX, by, row, "Vol (double saut) vers pests", () -> cfg.pestFly, v -> cfg.pestFly = v);
        by = toggle(rightX, by, row, "Rester dans le plot", () -> cfg.plotLimit, v -> cfg.plotLimit = v);
        by = toggle(rightX, by, row, "Retour au début après pests", () -> cfg.pestReturnToStart, v -> cfg.pestReturnToStart = v);

        by += 6;
        addRenderableWidget(Button.builder(Component.literal("Enregistrer mon angle actuel"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                cfg.farmYaw = mc.player.getYRot();
                cfg.farmPitch = mc.player.getXRot();
                cfg.saveActiveToProfile();
                b.setMessage(Component.literal("Angle enregistré : " + FarmConfig.cropName(cfg.activeCrop)));
            }
        }).bounds(rightX, by, 195, 20).build());
        by += row;

        // Points de début / fin du farm
        addRenderableWidget(Button.builder(Component.literal("Point début"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                cfg.setStart(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
                b.setMessage(Component.literal("§aDébut OK"));
            }
        }).bounds(rightX, by, 63, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Point fin"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                cfg.setEnd(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                b.setMessage(Component.literal("§aFin OK"));
            }
        }).bounds(rightX + 66, by, 63, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Effacer"), b -> {
            cfg.clearPoints();
            b.setMessage(Component.literal("§eEffacés"));
        }).bounds(rightX + 132, by, 63, 20).build());
        by += row;

        addRenderableWidget(Button.builder(Component.literal(macro.isRunning() ? "Arrêter la macro" : "Démarrer la macro"), b -> {
            applyFields();
            cfg.save();
            Minecraft mc = Minecraft.getInstance();
            if (macro.isRunning()) {
                macro.stop(mc);
            } else {
                macro.start(mc);
            }
            mc.setScreen(null);
        }).bounds(rightX, by, 195, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Enregistrer et fermer"), b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private int field(int x, int y, int row, String label, String value, Consumer<String> setter) {
        addRenderableWidget(new StringWidget(x, y + 5, 125, 12, Component.literal(label), this.font));
        EditBox box = new EditBox(this.font, x + 128, y, 72, 18, Component.literal(label));
        box.setMaxLength(200);
        box.setValue(value);
        addRenderableWidget(box);
        fieldApplyActions.add(() -> {
            try {
                setter.accept(box.getValue());
            } catch (RuntimeException ignored) {
                // valeur invalide : on garde l'ancienne
            }
        });
        return y + row;
    }

    private int toggle(int x, int y, int row, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        Button[] ref = new Button[1];
        ref[0] = Button.builder(toggleText(label, getter.getAsBoolean()), b -> {
            boolean now = !getter.getAsBoolean();
            setter.accept(now);
            b.setMessage(toggleText(label, now));
        }).bounds(x, y, 195, 20).build();
        addRenderableWidget(ref[0]);
        return y + row;
    }

    private static Component toggleText(String label, boolean on) {
        return Component.literal(label + " : " + (on ? "§aOUI" : "§cNON"));
    }

    private void applyFields() {
        for (Runnable r : fieldApplyActions) {
            r.run();
        }
    }

    @Override
    public void onClose() {
        applyFields();
        cfg.saveActiveToProfile();
        cfg.save();
        super.onClose();
    }
}
