package dev.farmassist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Set;

/**
 * Machine à états : FARMING (aller-retour en S, clic gauche maintenu)
 * -> PEST_HUNT (vise le pest le plus proche, avance, utilise l'outil) -> FARMING.
 */
public final class FarmMacro {
    public enum State { IDLE, FARMING, PEST_HUNT, TELEPORTING, RETURNING }

    private final FarmConfig cfg;

    private State state = State.IDLE;
    private boolean movingRight;
    private int stuckCounter;
    private Vec3 lastPos = Vec3.ZERO;
    private int tick;
    private int lastLoopTick = -1000;
    public int tabAlive = -1;          // pests vivants lus dans le tab (-1 = inconnu)
    private boolean pestsPending = false; // seuil atteint : chasser à la fin du passage de farm
    private int tabSuppressUntil = 0;
    public int loops = 0;

    // --- Voyage entre plots ---
    private final LinkedHashSet<String> pendingPlots = new LinkedHashSet<>();
    private int pendingPestCount = 0;
    private boolean inTrip = false;
    private String currentPlot = null;   // plot sur lequel on chasse en ce moment (voyage)
    private long plotStartMs = 0;        // début de la chasse sur ce plot
    private final Set<String> failedPlots = new java.util.HashSet<>(); // plots abandonnés (temps max) pendant ce run
    private int tpWait = 0;
    private State afterTeleport = State.FARMING;
    private Vec3 tpFromPos = null;       // position juste avant l'envoi du /plottp (sert à détecter l'arrivée réelle)
    private int tpExtraWait = 0;         // ticks d'attente en plus tant que la téléportation n'a pas eu lieu
    public String lastHuntInfo = "";    // dernier plot de chasse calculé (affiché par /farmassist pests)

    private boolean returnToStartFlag = false; // le tp vers le point de début est parti : à l'arrivée, le farm reprend depuis le début

    private Vec3 returnPos;
    private long returnStartMs;

    private Entity huntTarget;
    private int noTargetTicks;
    private int extraRounds = 0;   // relances de chasse décidées parce que le tab indiquait encore des pests
    private Entity shrinkTarget;        // cible pour laquelle on mesure le rapprochement
    private int inRangeTicks = 0;       // ticks passés à portée de la même cible sans qu'elle meure
    private double approachShrink = 0;  // blocs de rapprochement en plus (portée réelle plus courte que prévu)
    private boolean searching = false;  // aucun pest trouvé alors qu'il en reste : on se balade pour les chercher
    private Vec3 searchCenter = null;   // centre de la balade (là où on est arrivé sur le plot)
    private int searchIdx = 0;          // point de balade en cours
    private int searchWpTicks = 0;      // ticks passés vers ce point (on passe au suivant si trop long)
    private int trailClickTick = -1000; // tick du dernier clic gauche de recherche
    private Vec3 trailGoal = null;      // point visé d'après la traînée
    private int trailMisses = 0;        // clics sans traînée reçue (le serveur n'en envoie peut-être pas)
    private int settleTicks = 0;        // ticks d'attente après l'arrivée (chargement des entités) avant de chercher
    private java.util.List<Vec3> sweepPts = null; // balayage systématique du plot (aller-retour), remplace la balade au hasard
    private int sweepIdx = 0;
    private int wanderCheckTick = -1000;
    private boolean wanderAllowed = true;
    private double[] huntBounds = null;  // {minX, maxX, minZ, maxZ} du plot où on chasse (limite à ne pas franchir)
    private long huntStartMs;
    private final FlyNavigator nav = new FlyNavigator();
    private Vec3 flyStuckRef = Vec3.ZERO;
    private int flyStuckTicks = 0;
    private int djStep = -1;   // étape du double saut en cours (-1 = aucun)
    private int djTries = 0;   // nombre de doubles sauts déjà tentés pour couper le fly

    private long startedAtMs;
    private boolean prevPauseOnLostFocus = true;
    public int pestsKilled = 0;

    public FarmMacro(FarmConfig cfg) {
        this.cfg = cfg;
    }

    public State state() { return state; }

    /** Plot (case de la grille) qui contient le point (x, z) : {minX, maxX, minZ, maxZ}. */
    public double[] plotCell(double x, double z) {
        double size = cfg.plotSize;
        double minX = Math.floor((x - cfg.plotOffsetX) / size) * size + cfg.plotOffsetX;
        double minZ = Math.floor((z - cfg.plotOffsetZ) / size) * size + cfg.plotOffsetZ;
        return new double[]{minX, minX + size, minZ, minZ + size};
    }

    /** Cellule du plot d'après son numéro et cfg.plotLayout (grille 5x5, 0 = grange). null si inconnu. */
    private double[] cellFromPlotNumber(String plot) {
        if (plot == null || cfg.plotLayout == null || cfg.plotLayout.isBlank()) return null;
        String[] rows = cfg.plotLayout.split("/");
        int mid = rows.length / 2;
        for (int r = 0; r < rows.length; r++) {
            String[] cols = rows[r].split(",");
            int midC = cols.length / 2;
            for (int c = 0; c < cols.length; c++) {
                if (cols[c].trim().equals(plot.trim()) && !plot.trim().equals("0")) {
                    double size = cfg.plotSize;
                    double minX = cfg.plotOffsetX + (c - midC) * size;
                    double minZ = cfg.plotOffsetZ + (r - mid) * size;
                    return new double[]{minX, minX + size, minZ, minZ + size};
                }
            }
        }
        return null;
    }

    private static boolean inside(double[] b, double x, double z, double margin) {
        return x >= b[0] + margin && x <= b[1] - margin && z >= b[2] + margin && z <= b[3] - margin;
    }

    /** Ramène un point dans le plot (avec la marge). */
    private Vec3 clampIntoPlot(Vec3 p) {
        double m = Math.min(cfg.plotMargin, (huntBounds[1] - huntBounds[0]) / 4);
        return new Vec3(Mth.clamp(p.x, huntBounds[0] + m, huntBounds[1] - m), p.y,
                Mth.clamp(p.z, huntBounds[2] + m, huntBounds[3] - m));
    }

    /** Plot à respecter pour choisir les pests : celui de la chasse en cours, sinon celui où on se trouve. */
    private double[] activeBounds(LocalPlayer player) {
        if (!cfg.plotLimit) return null;
        if (state == State.PEST_HUNT && huntBounds != null) return huntBounds;
        return plotCell(player.getX(), player.getZ());
    }
    public boolean isPestsPending() { return pestsPending; }

    /** Nombre de pests détectés autour du joueur (pour la commande de diagnostic). */
    public int countNearbyPests(Minecraft mc) {
        if (mc.player == null || mc.level == null) return 0;
        return findPests(mc, mc.player).size();
    }
    public boolean isRunning() { return state != State.IDLE; }
    public long elapsedSeconds() { return isRunning() ? (System.currentTimeMillis() - startedAtMs) / 1000 : 0; }

    public void start(Minecraft mc) {
        if (mc.player == null) return;
        // Culture : détection auto d'après le bloc visé (ou profil choisi), puis on applique angle / outil / direction
        if (cfg.isAutoCrop()) {
            String detected = detectCrop(mc);
            if (detected != null) cfg.activeCrop = detected;
        }
        cfg.applyCrop(cfg.activeCrop);
        movingRight = cfg.startRight;
        stuckCounter = 0;
        lastPos = mc.player.position();
        startedAtMs = System.currentTimeMillis();
        // Continuer à tourner quand la fenêtre n'a plus le focus (autre appli au premier plan)
        prevPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        state = State.FARMING;

        // Au démarrage : s'il y a déjà assez de pests, on les chasse AVANT de farmer.
        // Sinon on farme, et la détection en cours de farm (tickFarming) prend le relais.
        pestsPending = false;
        if (cfg.pestsEnabled && pestsOverThreshold(mc)) {
            startPestRun(mc, mc.player);
        }
    }

    /** Vrai si le nombre de pests atteint le seuil (tab en priorité, sinon pests détectés autour du joueur). */
    private boolean pestsOverThreshold(Minecraft mc) {
        if (cfg.pestTabEnabled) {
            int alive = readPestsAlive(mc);
            tabAlive = alive;
            if (alive >= 0) return alive >= cfg.pestTabThreshold;
        }
        return countNearbyPests(mc) >= cfg.pestThreshold;
    }

    /** Culture devant le viseur (d'après l'identifiant du bloc), ou null si on ne regarde pas une culture. */
    private String detectCrop(Minecraft mc) {
        if (mc.level != null && mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).getPath();
            return FarmConfig.cropFromBlockId(id);
        }
        return null;
    }

    public void stop(Minecraft mc) {
        if (state != State.IDLE) {
            mc.options.pauseOnLostFocus = prevPauseOnLostFocus;
        }
        state = State.IDLE;
        returnToStartFlag = false;
        huntTarget = null;
        inTrip = false;
        currentPlot = null;
        failedPlots.clear();
        pestsPending = false;
        tabAlive = -1;
        pendingPlots.clear();
        pendingPestCount = 0;
        releaseKeys(mc);
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (state == State.IDLE) return;
        if (player == null || mc.level == null) {
            stop(mc);
            return;
        }
        // Pause de sécurité quand un menu/chat est ouvert
        boolean chatOnly = mc.screen instanceof ChatScreen && cfg.farmWhileChatOpen;
        if (mc.screen != null && !chatOnly) {
            releaseKeys(mc);
            return;
        }
        tick++;

        switch (state) {
            case FARMING -> tickFarming(mc, player);
            case PEST_HUNT -> tickPestHunt(mc, player);
            case TELEPORTING -> tickTeleporting(mc, player);
            case RETURNING -> tickReturning(mc, player);
            default -> { }
        }
    }

    // ------------------------------------------------------------ FARMING

    private void tickFarming(Minecraft mc, LocalPlayer player) {
        player.getInventory().setSelectedSlot(cfg.farmSlot);
        rotateTowards(player, cfg.farmYaw, cfg.farmPitch);

        // Détection de pests toutes les 10 ticks
        if (cfg.pestsEnabled) {
            if (cfg.pestTabEnabled && tick % 20 == 0) {
                tabAlive = readPestsAlive(mc);
                if (tabAlive >= cfg.pestTabThreshold && tick >= tabSuppressUntil) {
                    pestsPending = true;
                }
            }
            if (cfg.pestTabEnabled && tabAlive >= 0) {
                // Mode tab : on termine le passage de farm, puis on chasse (voir "point de fin" plus bas).
                // Sans points début/fin, on chasse tout de suite.
                if (pestsPending && (cfg.end() == null || cfg.start() == null)) {
                    pestsPending = false;
                    startPestRun(mc, player);
                    return;
                }
            } else if (tick % 10 == 0) {
                // Tab illisible : ancien mode (annonces du chat, ou pests proches)
                if (cfg.plotTeleport && pendingPestCount >= cfg.pestThreshold && !pendingPlots.isEmpty()) {
                    startTrip(mc, player);
                    return;
                }
                if (findPests(mc, player).size() >= cfg.pestThreshold) {
                    startHunt(mc, player);
                    return;
                }
            }
        }

        // Arrivée au point de fin -> retour au point de début
        double[] endP = cfg.end();
        double[] startP = cfg.start();
        if (endP != null && startP != null && tick - lastLoopTick > 40) {
            Vec3 p = player.position();
            double dh = Math.hypot(p.x - endP[0], p.z - endP[2]);
            if (dh <= cfg.endRadius && Math.abs(p.y - endP[1]) < 3) {
                if (pestsPending && cfg.pestsEnabled) {
                    // Passage terminé : on chasse les pests, puis on reviendra ici et on retournera au début
                    pestsPending = false;
                    startPestRun(mc, player);
                    return;
                }
                loopToStart(mc, player, startP);
                return;
            }
        }

        // Détection de blocage (mur au bout de la rangée) -> on inverse la direction
        Vec3 pos = player.position();
        double dx = pos.x - lastPos.x;
        double dz = pos.z - lastPos.z;
        double moved;
        if (cfg.holdForward) {
            // On avance en permanence : on ne mesure que le déplacement latéral (axe droite/gauche)
            double yawRad = Math.toRadians(player.getYRot());
            moved = Math.abs(dx * -Math.cos(yawRad) + dz * -Math.sin(yawRad));
        } else {
            moved = Math.hypot(dx, dz);
        }
        lastPos = pos;
        if (moved < 0.01) {
            if (++stuckCounter >= cfg.stuckTicks) {
                movingRight = !movingRight;
                stuckCounter = 0;
            }
        } else {
            stuckCounter = 0;
        }

        mc.options.keyAttack.setDown(true);
        if (mc.screen != null) {
            attackManually(mc, player); // le clic maintenu est ignoré quand un écran est ouvert
        }
        mc.options.keyRight.setDown(movingRight);
        mc.options.keyLeft.setDown(!movingRight);
        mc.options.keyUp.setDown(cfg.holdForward);
        mc.options.keyUse.setDown(false);
    }

    /** Casse le bloc visé sans passer par la touche (utile quand le chat est ouvert). */
    private void attackManually(Minecraft mc, LocalPlayer player) {
        if (mc.gameMode != null && mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void startPestRun(Minecraft mc, LocalPlayer player) {
        extraRounds = 0;
        failedPlots.clear();
        tabSuppressUntil = tick + 200; // laisse le tab se mettre à jour après la chasse
        if (cfg.plotTeleport) {
            // Les plots infestés sont lus dans le tab (ligne « Plots: 2, 3, 4 »)
            java.util.List<String> plots = readPestPlots(mc);
            if (!plots.isEmpty()) {
                pendingPlots.clear();
                pendingPlots.addAll(plots);
            }
            if (!pendingPlots.isEmpty()) {
                startTrip(mc, player);
                return;
            }
        }
        startHunt(mc, player);
    }

    /** Lit dans le tab les plots où il y a des pests (ligne qui correspond à pestPlotsRegex). */
    public java.util.List<String> readPestPlots(Minecraft mc) {
        java.util.List<String> plots = new java.util.ArrayList<>();
        if (mc.getConnection() == null) return plots;
        Pattern pat;
        try {
            pat = Pattern.compile(cfg.pestPlotsRegex);
        } catch (RuntimeException e) {
            return plots;
        }
        for (var info : mc.getConnection().getOnlinePlayers()) {
            net.minecraft.network.chat.Component c = info.getTabListDisplayName();
            if (c == null) continue;
            Matcher m = pat.matcher(c.getString());
            if (m.find()) {
                for (String part : m.group(1).split(",")) {
                    part = part.trim();
                    if (!part.isEmpty()) plots.add(part);
                }
                return plots;
            }
        }
        return plots;
    }

    /** Lit dans le tab le nombre de pests vivants (ligne qui correspond à pestTabRegex). -1 si introuvable. */
    public int readPestsAlive(Minecraft mc) {
        if (mc.getConnection() == null) return -1;
        Pattern pat;
        try {
            pat = Pattern.compile(cfg.pestTabRegex);
        } catch (RuntimeException e) {
            return -1;
        }
        for (var info : mc.getConnection().getOnlinePlayers()) {
            net.minecraft.network.chat.Component c = info.getTabListDisplayName();
            if (c == null) continue;
            Matcher m = pat.matcher(c.getString());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (RuntimeException ignored) {
                    // pas un nombre : on continue
                }
            }
        }
        return -1;
    }

    private String startTpCmd(double[] st) {
        return cfg.startTpCommand
                .replace("%yaw", fmt(st[3]))
                .replace("%pitch", fmt(st[4]))
                .replace("%x", fmt(st[0]))
                .replace("%y", fmt(st[1]))
                .replace("%z", fmt(st[2]));
    }

    private void loopToStart(Minecraft mc, LocalPlayer player, double[] st) {
        releaseKeys(mc);
        player.connection.sendCommand(startTpCmd(st));
        loops++;
        lastLoopTick = tick;
        tpWait = cfg.loopTpDelayTicks;
        afterTeleport = State.FARMING;
        state = State.TELEPORTING;
    }

    /** Vrai si, une fois les pests finis, on se téléporte au point de début au lieu de revenir à l'endroit exact du farm. */
    private boolean wantsStartReturn() {
        return cfg.pestReturnToStart && cfg.start() != null;
    }

    /** Pests finis : tp au point de début, puis on coupe le vol (état RETURNING) et le farm reprend depuis le début. */
    private void teleportToStartAfterHunt(Minecraft mc, LocalPlayer player) {
        double[] st = cfg.start();
        releaseKeys(mc);
        player.connection.sendCommand(startTpCmd(st));
        returnPos = new Vec3(st[0], st[1], st[2]);
        returnToStartFlag = true;
        tpWait = cfg.loopTpDelayTicks;
        afterTeleport = State.RETURNING;
        state = State.TELEPORTING;
    }

    /** Fin du retour : on reprend le farm (depuis le début si on vient d'être téléporté au point de début). */
    private void resumeFarming(LocalPlayer player) {
        lastPos = player.position();
        stuckCounter = 0;
        if (returnToStartFlag) {
            returnToStartFlag = false;
            movingRight = cfg.startRight;
            lastLoopTick = tick;
        }
        state = State.FARMING;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    // ------------------------------------------------------------ PESTS

    private void startHunt(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);
        returnPos = player.position(); // endroit du farm, pour y revenir après
        resetSearch(player.position());
        state = State.PEST_HUNT;
        huntTarget = null;
        noTargetTicks = 0;
        settleTicks = 0;
        huntStartMs = System.currentTimeMillis();
    }

    private void tickPestHunt(Minecraft mc, LocalPlayer player) {
        // Abandon si la chasse dure trop longtemps
        if (System.currentTimeMillis() - huntStartMs > cfg.pestTimeoutSeconds * 1000L) {
            endHunt(mc, player);
            return;
        }

        player.getInventory().setSelectedSlot(cfg.vacuumSlot);

        // Limite du plot : navigation bornée, et si on en est sorti (poussé, tombé...) on y revient tout de suite
        nav.setBounds(cfg.plotLimit ? huntBounds : null, 1.0);
        if (cfg.plotLimit && huntBounds != null && !inside(huntBounds, player.getX(), player.getZ(), 0.0)) {
            mc.options.keyUse.setDown(false);
            mc.options.keyAttack.setDown(false);
            Vec3 back = clampIntoPlot(player.position());
            if (cfg.pestFly && player.getAbilities().mayfly) {
                if (!player.getAbilities().flying) {
                    stopMoveKeys(mc);
                    doubleJump(mc);
                    return;
                }
                djStep = -1;
                flyStep(mc, player, back, 1.0);
            } else {
                Vec3 pos = player.position();
                float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(back.z - pos.z, back.x - pos.x)) - 90.0));
                rotateTowards(player, yaw, 0f);
                mc.options.keyUp.setDown(true);
                mc.options.keyJump.setDown(player.horizontalCollision);
            }
            return;
        }

        // Cible morte/disparue -> comptée comme un kill
        if (huntTarget != null && (huntTarget.isRemoved() || !huntTarget.isAlive())) {
            pestsKilled++;
            huntTarget = null;
        }
        if (huntTarget == null) {
            huntTarget = findNearestPest(mc, player);
        }

        if (huntTarget == null) {
            // Aucun pest en vue : tant que le tab dit qu'il en reste, on se balade au hasard dans le plot pour les trouver
            mc.options.keyUse.setDown(false);
            // Juste après l'arrivée : on laisse les entités se charger (on rescanne chaque tick) avant de partir chercher
            if (settleTicks > 0) {
                settleTicks--;
                stopMoveKeys(mc);
                return;
            }
            if (cfg.pestTrail && cfg.pestSearch && trailMisses < 3 && (!cfg.pestTabEnabled || tabAlive != 0)
                    && (tabAlive < 0 || wanderAllowed) && trailStep(mc, player)) {
                return;
            }
            if (cfg.pestSearch && cfg.pestTabEnabled && tick - wanderCheckTick >= 20) {
                wanderCheckTick = tick;
                tabAlive = readPestsAlive(mc);
                wanderAllowed = shouldWander(mc);
            }
            boolean tabKnown = cfg.pestTabEnabled && tabAlive >= 0;
            boolean wander = cfg.pestSearch && (tabKnown ? (tabAlive > 0 && wanderAllowed) : searching);
            if (wander) {
                searchStep(mc, player);
                noTargetTicks = 0; // on cherche : seul pestTimeoutSeconds arrête la balade
                return;
            }
            mc.options.keyUp.setDown(false);
            int grace = (inTrip || (cfg.pestHuntUntilZero && cfg.pestTabEnabled)) ? 60 : 20;
            if (++noTargetTicks >= grace) {
                endHunt(mc, player);
            }
            return;
        }
        noTargetTicks = 0;

        if (huntTarget != shrinkTarget) {
            // nouvelle cible : on repart de la distance d'approche normale
            shrinkTarget = huntTarget;
            inRangeTicks = 0;
            approachShrink = 0;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 target = huntTarget.getBoundingBox().getCenter();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        double dist = eye.distanceTo(target);

        mc.options.keyAttack.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);

        // Portée réelle de l'aspirateur (lue d'après son nom) ; sinon valeurs de la config
        double vacRange = vacuumRange(player);
        double approach = vacRange > 0 ? Math.max(vacRange - cfg.pestFlyStopDist, 3) : cfg.pestUseRange;
        // Si on aspire depuis un moment sans que le pest meure, la vraie portée est plus courte que prévu : on se rapproche
        // On se colle au pest : s'arrêter à la limite de portée est peu fiable (portée réelle plus courte, pest qui bouge)
        approach = Math.min(approach, cfg.pestCloseDist);
        approach = Math.max(approach - approachShrink, 1.5);
        double useRange = vacRange > 0 ? vacRange : cfg.pestUseRange + 2;

        if (cfg.pestFly && player.getAbilities().mayfly) {
            // Pas encore en vol : vrai double saut (appui, relâche, appui)
            if (!player.getAbilities().flying) {
                stopMoveKeys(mc);
                doubleJump(mc);
                return;
            }
            djStep = -1;
            djTries = 0;
            if (dist <= approach) {
                // À portée : on se tourne vers le pest, on s'immobilise et on aspire
                rotateTowards(player, yaw, pitch);
                stopMoveKeys(mc);
                mc.options.keyJump.setDown(false);
            } else {
                // Trop loin : on y va en volant, en contournant les blocs (pathfinder A*)
                flyStep(mc, player, target, approach);
            }
        } else {
            rotateTowards(player, yaw, pitch);
            mc.options.keyUp.setDown(dist > approach);
        }

        if (dist <= approach) {
            if (++inRangeTicks >= 40) {   // 2 s à portée sans résultat : on se rapproche encore d'un bloc
                approachShrink += 1.0;
                inRangeTicks = 0;
            }
        } else {
            inRangeTicks = 0;
        }

        boolean using = dist <= useRange;
        mc.options.keyUse.setDown(using);
        if (mc.screen != null && using && tick % 4 == 0 && mc.gameMode != null) {
            // clic droit ignoré quand un écran est ouvert : on utilise l'objet directement
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Faut-il fouiller CE plot ? Le nombre de pests du tab est global (tous les plots) : s'il reste des pests
     * seulement ailleurs, on ne se balade pas ici pour rien, on termine la chasse et on passe au plot suivant.
     */
    private boolean shouldWander(Minecraft mc) {
        if (!cfg.pestTabEnabled || tabAlive <= 0) return tabAlive < 0;
        String here = inTrip ? currentPlot : (cfg.farmPlot.isEmpty() ? null : cfg.farmPlot);
        if (here == null) return true;                 // plot courant inconnu : ancien comportement
        java.util.List<String> infested = readPestPlots(mc);
        if (infested.isEmpty()) return true;           // ligne des plots illisible : on ne peut pas savoir
        for (String p : infested) {
            if (p.equalsIgnoreCase(here)) return true;
        }
        return false;                                  // le tab ne liste plus ce plot : il est propre
    }

    /**
     * Comme le script de la vidéo : clic gauche avec l'aspirateur (le serveur affiche une traînée de particules vers
     * le pest le plus proche), puis on suit cette ligne en vol direct. Renvoie false si ça ne donne rien (on retombe
     * alors sur le balayage du plot).
     */
    private boolean trailStep(Minecraft mc, LocalPlayer player) {
        TrailTracker.tick = tick;
        boolean canFly = cfg.pestFly && player.getAbilities().mayfly;
        if (canFly && !player.getAbilities().flying) {
            stopMoveKeys(mc);
            doubleJump(mc);
            return true;
        }
        djStep = -1;
        djTries = 0;

        int phase = tick - trailClickTick;
        if (phase >= cfg.trailClickTicks) {
            trailClickTick = tick;
            TrailTracker.begin(tick);
            mc.options.keyAttack.setDown(true);       // clic gauche : fait apparaître la traînée
            phase = 0;
        } else {
            mc.options.keyAttack.setDown(false);
        }
        if (phase == 8) {                              // on laisse ~0,4 s aux particules pour arriver
            Vec3 aim = TrailTracker.aim(trailClickTick, player.getEyePosition(), cfg.trailParticle);
            if (aim != null) {
                trailGoal = (cfg.plotLimit && huntBounds != null) ? clampIntoPlot(aim) : aim;
                trailMisses = 0;
            } else if (++trailMisses >= 3) {
                trailGoal = null;
                mc.options.keyAttack.setDown(false);
                return false;
            }
        }
        if (trailGoal == null) {                       // pas encore de direction : on attend sur place
            stopMoveKeys(mc);
            return true;
        }
        Vec3 pos = player.position();
        if (pos.distanceTo(trailGoal) < 2.5) {
            trailGoal = null;                          // arrivé au bout : le prochain clic donnera la suite
            stopMoveKeys(mc);
            return true;
        }
        if (canFly) {
            flyStep(mc, player, trailGoal, 2.0);
        } else {
            float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(trailGoal.z - pos.z, trailGoal.x - pos.x)) - 90.0));
            rotateTowards(player, yaw, 0f);
            mc.options.keyUp.setDown(true);
            mc.options.keyJump.setDown(player.horizontalCollision);
        }
        return true;
    }

    private void endHunt(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);
        huntTarget = null;
        if (inTrip) {
            // Plot où on vient de se téléporter : on ne le quitte pas tant que le tab dit qu'il reste des pests dedans
            if (currentPlotStillInfested(mc)) {
                restartHuntInPlace();
                return;
            }
            nextPlot(mc, player);
            return;
        }
        // Chasse lancée : on continue jusqu'à 0 pest (tab), pas seulement jusqu'à repasser sous le seuil
        if (continueUntilClear(mc)) {
            if (cfg.plotTeleport) {
                pendingPlots.clear();
                addInfestedPlots(mc);
                if (!pendingPlots.isEmpty()) {
                    inTrip = true;          // returnPos (position du farm) est conservée
                    pendingPestCount = 0;
                    nextPlot(mc, player);
                    return;
                }
            }
            restartHuntInPlace();
            return;
        }
        if (wantsStartReturn()) {
            teleportToStartAfterHunt(mc, player);
            return;
        }
        returnStartMs = System.currentTimeMillis();
        state = State.RETURNING;
    }

    /**
     * Vrai si le tab indique encore des pests vivants (> 0) et qu'on n'a pas épuisé les relances.
     * Faux si l'option est coupée, si le tab est illisible (-1) ou s'il indique 0.
     */
    private boolean continueUntilClear(Minecraft mc) {
        if (!cfg.pestHuntUntilZero || !cfg.pestTabEnabled) return false;
        if (extraRounds >= cfg.pestMaxRounds) return false;
        int alive = readPestsAlive(mc);
        tabAlive = alive;
        if (alive <= 0) return false;
        extraRounds++;
        return true;
    }

    /** Relance une chasse là où on est (mêmes cible/chrono remis à zéro, returnPos inchangée). */
    private void restartHuntInPlace() {
        huntTarget = null;
        noTargetTicks = 0;
        huntStartMs = System.currentTimeMillis();
        searching = true;   // il reste des pests mais on n'en voit pas : on se balade pour les trouver
        state = State.PEST_HUNT;
    }

    /**
     * Vrai si le plot où on chasse est encore listé comme infesté dans le tab (donc on y reste).
     * Faux si l'option est coupée, si le tab ne le liste plus, ou si on a dépassé plotMaxSeconds
     * (dans ce cas le plot est abandonné pour ce run, pour ne jamais rester bloqué).
     */
    private boolean currentPlotStillInfested(Minecraft mc) {
        if (!cfg.plotMustClear || currentPlot == null || !cfg.pestTabEnabled) return false;
        if (failedPlots.contains(currentPlot)) return false;
        if (System.currentTimeMillis() - plotStartMs > cfg.plotMaxSeconds * 1000L) {
            failedPlots.add(currentPlot);
            return false;
        }
        for (String plot : readPestPlots(mc)) {
            if (plot.equalsIgnoreCase(currentPlot)) return true;
        }
        return false;
    }

    /** Ajoute aux plots à visiter ceux que le tab indique infestés (sauf ceux déjà abandonnés). */
    private void addInfestedPlots(Minecraft mc) {
        for (String plot : readPestPlots(mc)) {
            if (!failedPlots.contains(plot)) pendingPlots.add(plot);
        }
    }

    // ------------------------------------------------------------ RECHERCHE (balade)

    private void resetSearch(Vec3 center) {
        huntBounds = cfg.plotLimit ? plotCell(center.x, center.z) : null;
        wanderGoal = null;
        sweepPts = null;
        sweepIdx = 0;
        trailGoal = null;
        trailMisses = 0;
        trailClickTick = -1000;
        searching = false;
        searchCenter = center;
        searchIdx = 0;
        searchWpTicks = 0;
    }

    private final java.util.Random rng = new java.util.Random();
    private Vec3 wanderGoal = null;     // point de balade en cours (tiré au hasard dans le plot)

    /** Point de balade tiré au hasard dans le plot (ou, sans limite de plot, dans un disque autour de l'arrivée). */
    private Vec3 searchWaypoint(Vec3 from) {
        if (wanderGoal == null && cfg.plotLimit && huntBounds != null) {
            // Balayage systématique en serpentin : on couvre tout le plot sans repasser au même endroit
            if (sweepPts == null) buildSweep(from);
            wanderGoal = sweepPts.get(sweepIdx);
            sweepIdx = (sweepIdx + 1) % sweepPts.size();
            return wanderGoal;
        }
        if (wanderGoal == null) {
            for (int i = 0; i < 8; i++) {
                double px, pz;
                if (cfg.plotLimit && huntBounds != null) {
                    double m = Math.min(cfg.plotMargin, (huntBounds[1] - huntBounds[0]) / 4);
                    px = huntBounds[0] + m + rng.nextDouble() * (huntBounds[1] - huntBounds[0] - 2 * m);
                    pz = huntBounds[2] + m + rng.nextDouble() * (huntBounds[3] - huntBounds[2] - 2 * m);
                } else {
                    double a = rng.nextDouble() * Math.PI * 2;
                    double d = cfg.pestSearchRadius * Math.sqrt(rng.nextDouble());
                    px = searchCenter.x + Math.cos(a) * d;
                    pz = searchCenter.z + Math.sin(a) * d;
                }
                wanderGoal = new Vec3(px, searchCenter.y + 1.0 + rng.nextDouble() * 3.0, pz);
                if (Math.hypot(px - from.x, pz - from.z) >= 5.0) break; // pas trop près de nous
            }
        }
        return wanderGoal;
    }

    /** Construit le serpentin (lignes parallèles à X, espacées de pestSweepSpacing) et démarre au point le plus proche. */
    private void buildSweep(Vec3 from) {
        double m = Math.min(cfg.plotMargin + 2, (huntBounds[1] - huntBounds[0]) / 4);
        double x0 = huntBounds[0] + m, x1 = huntBounds[1] - m;
        double z0 = huntBounds[2] + m, z1 = huntBounds[3] - m;
        int rows = Math.max(2, (int) Math.ceil((z1 - z0) / cfg.pestSweepSpacing) + 1);
        double y = (searchCenter != null ? searchCenter.y : from.y) + 3.0;
        java.util.List<Vec3> pts = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            double z = z0 + (z1 - z0) * i / (rows - 1);
            if (i % 2 == 0) { pts.add(new Vec3(x0, y, z)); pts.add(new Vec3(x1, y, z)); }
            else            { pts.add(new Vec3(x1, y, z)); pts.add(new Vec3(x0, y, z)); }
        }
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = Math.hypot(pts.get(i).x - from.x, pts.get(i).z - from.z);
            if (d < bd) { bd = d; best = i; }
        }
        sweepPts = pts;
        sweepIdx = best;
    }

    /** Fait un pas de balade autour du centre du plot pour chercher des pests qu'on ne voit pas encore. */
    private void searchStep(Minecraft mc, LocalPlayer player) {
        if (searchCenter == null) searchCenter = player.position();
        boolean canFly = cfg.pestFly && player.getAbilities().mayfly;
        if (canFly && !player.getAbilities().flying) {
            stopMoveKeys(mc);
            doubleJump(mc);
            return;
        }
        djStep = -1;
        djTries = 0;

        Vec3 pos = player.position();
        Vec3 wp = searchWaypoint(pos);
        if (++searchWpTicks > 300 || Math.hypot(wp.x - pos.x, wp.z - pos.z) < 2.0) {
            wanderGoal = null;      // arrivé (ou bloqué trop longtemps) : nouveau point au hasard
            searchWpTicks = 0;
            wp = searchWaypoint(pos);
        }
        if (canFly) {
            flyStep(mc, player, wp, 1.5);
        } else {
            float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(wp.z - pos.z, wp.x - pos.x)) - 90.0));
            rotateTowards(player, yaw, 0f);
            mc.options.keyUp.setDown(true);
            mc.options.keyJump.setDown(player.horizontalCollision);
        }
    }

    // ------------------------------------------------------------ PLOTS

    /** Appelé pour chaque message du chat : repère les annonces de pests et le plot concerné. */
    public void onChat(String text) {
        if (!isRunning() || !cfg.pestsEnabled || !cfg.plotTeleport) return;
        for (String regex : cfg.pestChatRegex.split("\\|\\|")) {
            try {
                Matcher m = Pattern.compile(regex.trim()).matcher(text);
                if (m.find()) {
                    String plot = null;
                    for (int i = 1; i <= m.groupCount() && plot == null; i++) plot = m.group(i);
                    if (plot != null) {
                        pendingPlots.add(plot);
                        pendingPestCount++;
                        return;
                    }
                }
            } catch (RuntimeException ignored) {
                // regex invalide : ignorée
            }
        }
    }

    private void sendPlotTp(LocalPlayer player, String plot) {
        tpFromPos = player.position();
        tpExtraWait = 0;
        player.connection.sendCommand(String.format(cfg.plotTpCommand, plot));
    }

    private void startTrip(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);
        returnPos = player.position();
        inTrip = true;
        pendingPestCount = 0;
        nextPlot(mc, player);
    }

    /** Va sur le prochain plot à nettoyer, ou rentre au plot de farm s'il n'y en a plus. */
    private void nextPlot(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);
        if (pendingPlots.isEmpty() && continueUntilClear(mc)) {
            // Plus de plot en attente mais le tab indique encore des pests : nouveaux plots ? sinon on chasse sur place
            if (cfg.plotTeleport) {
                addInfestedPlots(mc);
            }
            if (pendingPlots.isEmpty()) {
                restartHuntInPlace();
                return;
            }
        }
        if (!pendingPlots.isEmpty()) {
            String plot = pendingPlots.iterator().next();
            pendingPlots.remove(plot);
            sendPlotTp(player, plot);
            currentPlot = plot;
            afterTeleport = State.PEST_HUNT;
        } else {
            inTrip = false;
            currentPlot = null;
            if (!cfg.farmPlot.isEmpty()) {
                sendPlotTp(player, cfg.farmPlot);
            }
            afterTeleport = State.RETURNING;
        }
        tpWait = cfg.teleportDelayTicks;
        state = State.TELEPORTING;
    }

    private void tickTeleporting(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);
        if (--tpWait > 0) return;
        if (afterTeleport == State.PEST_HUNT && tpFromPos != null
                && player.position().distanceTo(tpFromPos) < 6.0 && tpExtraWait < 80) {
            // Le /plottp n'a pas encore déplacé le joueur (lag, délai du serveur) : si on calculait le plot
            // maintenant, ce serait l'ANCIEN plot (celui de derrière). On attend l'arrivée (max 4 s de plus).
            tpExtraWait++;
            return;
        }
        tpFromPos = null;
        tpExtraWait = 0;
        if (afterTeleport == State.PEST_HUNT) {
            huntTarget = null;
            noTargetTicks = 0;
            huntStartMs = System.currentTimeMillis();
            plotStartMs = huntStartMs;
            resetSearch(player.position());
            settleTicks = cfg.pestSettleTicks;
            wanderCheckTick = -1000;
            double[] byNumber = cellFromPlotNumber(currentPlot);
            if (byNumber != null && cfg.plotLimit) {
                // Plot déduit du NUMÉRO (fiable), pas de la position d'arrivée (qui peut tomber sur le plot voisin).
                // Garde-fou : si on n'est pas dedans (à 12 blocs près), la grille ne correspond pas : on garde la position.
                if (inside(byNumber, player.getX(), player.getZ(), -12.0)) {
                    huntBounds = byNumber;
                    nav.setBounds(huntBounds, 1.0);
                }
            }
            if (huntBounds != null) {
                lastHuntInfo = String.format(java.util.Locale.ROOT,
                        "plot %s : zone chassée X %.0f à %.0f | Z %.0f à %.0f (arrivée en x=%.1f z=%.1f)",
                        currentPlot, huntBounds[0], huntBounds[1], huntBounds[2], huntBounds[3],
                        player.getX(), player.getZ());
            }
            state = State.PEST_HUNT;
        } else if (afterTeleport == State.FARMING) {
            lastPos = player.position();
            stuckCounter = 0;
            movingRight = cfg.startRight;
            lastLoopTick = tick;
            state = State.FARMING;
        } else {
            if (!returnToStartFlag && wantsStartReturn()) {
                teleportToStartAfterHunt(mc, player); // de retour sur le plot de farm : maintenant on va au point de début
                return;
            }
            returnStartMs = System.currentTimeMillis();
            state = State.RETURNING;
        }
    }

    /** Retourne à l'endroit exact où le farm a été interrompu, puis reprend le farm. */
    private void tickReturning(Minecraft mc, LocalPlayer player) {
        nav.setBounds(null, 0);
        player.getInventory().setSelectedSlot(cfg.farmSlot);
        Vec3 pos = player.position();
        double dx = returnPos.x - pos.x;
        double dz = returnPos.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (cfg.pestFly && player.getAbilities().mayfly) {
            double dy = returnPos.y - pos.y;
            boolean arrived = dist < 0.5 && Math.abs(dy) < 0.5;
            boolean timeout = System.currentTimeMillis() - returnStartMs > 20_000L;
            boolean flying = player.getAbilities().flying;

            if (arrived || timeout) {
                // Arrivé : on coupe le fly avec un vrai double saut avant de reprendre le farm
                if (flying && djTries < 3) {
                    stopMoveKeys(mc);
                    doubleJump(mc);
                    return;
                }
                releaseKeys(mc);
                djTries = 0;
                resumeFarming(player);
                return;
            }
            if (!flying) {
                // Pas en vol (ex: après un plottp) : double saut pour réactiver le fly
                stopMoveKeys(mc);
                doubleJump(mc);
                return;
            }
            djStep = -1;
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
            flyStep(mc, player, returnPos, 0.9);
            return;
        }

        if (dist < 0.5 || System.currentTimeMillis() - returnStartMs > 20_000L) {
            releaseKeys(mc);
            resumeFarming(player);
            return;
        }
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        rotateTowards(player, yaw, 0f);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(player.horizontalCollision);
    }

    private List<Entity> findPests(Minecraft mc, LocalPlayer player) {
        Set<String> types = cfg.pestTypeSet();
        java.util.List<java.util.regex.Pattern> namePatterns = cfg.pestNamePatterns();
        double r = cfg.pestScanRadius;
        List<Entity> found = mc.level.getEntities(player, player.getBoundingBox().inflate(r), e -> {
            if (e == player || e.isRemoved() || !e.isAlive()) return false;
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (types.contains(typeId)) return true;
            if (!namePatterns.isEmpty() && e.hasCustomName()) {
                String name = e.getCustomName().getString();
                for (java.util.regex.Pattern pt : namePatterns) {
                    if (pt.matcher(name).find()) return true;
                }
            }
            return false;
        });
        double[] b = activeBounds(player);
        return found.stream()
                .filter(e -> !(e instanceof LivingEntity le) || le.getHealth() > 0)
                .filter(e -> b == null || inside(b, e.getX(), e.getZ(), 0.0)) // jamais un pest d'un autre plot
                .toList();
    }

    private Entity findNearestPest(Minecraft mc, LocalPlayer player) {
        return findPests(mc, player).stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    // ------------------------------------------------------------ UTILS

    private void rotateTowards(LocalPlayer player, float targetYaw, float targetPitch) {
        float dYaw = Mth.wrapDegrees(targetYaw - player.getYRot());
        float dPitch = targetPitch - player.getXRot();
        float max = cfg.rotationSpeed;
        player.setYRot(player.getYRot() + Mth.clamp(dYaw, -max, max));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(dPitch, -max, max), -90f, 90f));
    }

    // ------------------------------------------------------------ VOL (pathfinding)

    /** Fait un pas de vol vers {@code goal} en contournant les obstacles ; s'arrête à {@code reach} blocs. */
    private void flyStep(Minecraft mc, LocalPlayer player, Vec3 goal, double reach) {
        Vec3 pos = player.position();
        Vec3 wp = nav.waypoint(mc.level, pos, goal, reach, tick);
        double dx = wp.x - pos.x;
        double dy = wp.y - pos.y;
        double dz = wp.z - pos.z;
        double horiz = Math.hypot(dx, dz);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        if (horiz > 0.3) {
            rotateTowards(player, yaw, 0f);
        }
        boolean aligned = horiz <= 0.3 || Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 35f;
        boolean forward = horiz > 0.4 && aligned;

        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(forward && horiz > 6);
        mc.options.keyJump.setDown(dy > 0.45);
        mc.options.keyShift.setDown(dy < -0.45);

        // Anti-blocage : si on appuie sur avancer sans bouger, on marque la case devant comme bloquée et on recalcule
        if (forward && pos.distanceToSqr(flyStuckRef) < 0.0004) {
            if (++flyStuckTicks >= 25) {
                double len = Math.max(horiz, 0.001);
                nav.ban(net.minecraft.core.BlockPos.containing(
                        pos.x + dx / len * 0.9, pos.y + 0.1, pos.z + dz / len * 0.9));
                flyStuckTicks = 0;
            }
        } else {
            flyStuckRef = pos;
            flyStuckTicks = 0;
        }
    }

    private static final java.util.LinkedHashMap<String, Double> VACUUM_RANGES = new java.util.LinkedHashMap<>();
    static {
        // Le plus spécifique d'abord (« InfiniVacuum™ Hooverius » contient « InfiniVacuum »)
        VACUUM_RANGES.put("InfiniVacuum™ Hooverius", 15.0);
        VACUUM_RANGES.put("InfiniVacuum", 12.5);
        VACUUM_RANGES.put("Hyper Vacuum", 10.0);
        VACUUM_RANGES.put("Turbo Vacuum", 7.5);
        VACUUM_RANGES.put("Skymart Vacuum", 5.0);
    }

    /** Portée de l'aspirateur tenu (d'après son nom), ou -1 si on ne le reconnaît pas. */
    private double vacuumRange(LocalPlayer player) {
        try {
            String name = player.getInventory().getItem(cfg.vacuumSlot).getHoverName().getString();
            for (var entry : VACUUM_RANGES.entrySet()) {
                if (name.contains(entry.getKey())) return entry.getValue();
            }
        } catch (RuntimeException ignored) {
            // slot vide ou nom illisible : on retombe sur la config
        }
        return -1;
    }

    /**
     * Vrai double saut, comme un joueur : appui, relâche, appui en moins de 7 ticks.
     * Le jeu bascule alors le fly (on/off). À appeler à chaque tick jusqu'à ce que l'état de vol change.
     */
    private void doubleJump(Minecraft mc) {
        switch (djStep) {
            case -1, 0 -> { mc.options.keyJump.setDown(true);  djStep = 1; }
            case 1     -> { mc.options.keyJump.setDown(false); djStep = 2; }
            case 2     -> { mc.options.keyJump.setDown(false); djStep = 3; }
            case 3     -> { mc.options.keyJump.setDown(true);  djStep = 4; }
            default    -> { mc.options.keyJump.setDown(false); djStep = -1; djTries++; }
        }
    }

    private void stopMoveKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    private void releaseKeys(Minecraft mc) {
        djStep = -1;
        nav.clear();
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
    }
}
