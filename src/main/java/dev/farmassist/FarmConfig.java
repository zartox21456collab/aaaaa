package dev.farmassist;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Configuration simple, stockée dans config/farmassist.properties. */
public final class FarmConfig {
    private static final String DEFAULT_PEST_NAMES =
            "Fly,Cricket,Locust,Rat,Mosquito,Earthworm,Slug,Moth,Beetle,Dragonfly,Field Mouse,Mite";
    private static final int CONFIG_VERSION = 3;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("farmassist.properties");

    // --- Farm ---
    public int farmSlot = 0;            // slot hotbar de l'outil de farm (0-8)
    public float farmYaw = 0f;          // angle horizontal pendant le farm
    public float farmPitch = 0f;        // angle vertical pendant le farm
    public String startPoint = "";      // x,y,z,yaw,pitch du début du farm
    public String endPoint = "";        // x,y,z de la fin du farm
    public double endRadius = 2.0;      // distance à partir de laquelle on considère être arrivé à la fin
    public String startTpCommand = "tp @s %x %y %z %yaw %pitch"; // commande sans "/" pour revenir au début
    public int loopTpDelayTicks = 20;   // attente après la téléportation au début
    public boolean farmWhileChatOpen = true; // continuer à farmer quand le chat est ouvert
    public boolean holdForward = false; // garder 'avancer' appuyé pendant le farm (ex: melons/citrouilles)
    public boolean startRight = true;   // direction de départ
    public int stuckTicks = 8;          // ticks sans bouger avant de changer de direction
    public float rotationSpeed = 12f;   // degrés max par tick

    // --- Cultures : un profil (angle, outil, direction) par culture ---
    public static final String[] CROPS = {"WHEAT", "CARROT", "POTATO", "NETHER_WART", "SUGAR_CANE", "MELON", "PUMPKIN", "CACTUS", "COCOA", "MUSHROOM"};
    public static final String[] CROP_NAMES = {"Blé", "Carotte", "Pomme de terre", "Nether wart", "Canne à sucre", "Melon", "Citrouille", "Cactus", "Cacao", "Champignon"};
    public String crop = "AUTO";         // "AUTO" = détection d'après le bloc visé au démarrage, sinon le nom de la culture
    public String activeCrop = "WHEAT";  // profil actuellement appliqué
    public final Map<String, CropProfile> profiles = new LinkedHashMap<>();

    public static final class CropProfile {
        public float yaw = 0f;
        public float pitch = 0f;
        public int slot = 0;
        public boolean holdForward = false;
        public boolean startRight = true;
    }

    public FarmConfig() {
        for (String c : CROPS) profiles.put(c, new CropProfile());
        profiles.get("MELON").holdForward = true;
        profiles.get("PUMPKIN").holdForward = true;
    }

    public boolean isAutoCrop() {
        return "AUTO".equals(crop);
    }

    public static String cropName(String id) {
        for (int i = 0; i < CROPS.length; i++) {
            if (CROPS[i].equals(id)) return CROP_NAMES[i];
        }
        return id;
    }

    public String cropLabel() {
        return isAutoCrop() ? "Auto (" + cropName(activeCrop) + ")" : cropName(crop);
    }

    /** Reconnaît la culture d'après l'identifiant du bloc (ex: "wheat", "nether_wart"), ou null. */
    public static String cropFromBlockId(String id) {
        return switch (id) {
            case "wheat" -> "WHEAT";
            case "carrots" -> "CARROT";
            case "potatoes" -> "POTATO";
            case "nether_wart" -> "NETHER_WART";
            case "sugar_cane" -> "SUGAR_CANE";
            case "melon" -> "MELON";
            case "pumpkin" -> "PUMPKIN";
            case "cactus" -> "CACTUS";
            case "cocoa" -> "COCOA";
            case "brown_mushroom", "red_mushroom" -> "MUSHROOM";
            default -> null;
        };
    }

    /** Copie le profil de la culture vers les réglages actifs (angle, outil, direction). */
    public void applyCrop(String id) {
        CropProfile p = profiles.get(id);
        if (p == null) return;
        activeCrop = id;
        farmYaw = p.yaw;
        farmPitch = p.pitch;
        farmSlot = p.slot;
        holdForward = p.holdForward;
        startRight = p.startRight;
    }

    /** Range les réglages actifs dans le profil de la culture active. */
    public void saveActiveToProfile() {
        CropProfile p = profiles.get(activeCrop);
        if (p == null) return;
        p.yaw = farmYaw;
        p.pitch = farmPitch;
        p.slot = farmSlot;
        p.holdForward = holdForward;
        p.startRight = startRight;
    }

    /** AUTO -> Blé -> Carotte -> ... -> Champignon -> AUTO. */
    public void cycleCrop() {
        if (isAutoCrop()) {
            crop = CROPS[0];
        } else {
            int i = java.util.Arrays.asList(CROPS).indexOf(crop);
            crop = (i < 0 || i + 1 >= CROPS.length) ? "AUTO" : CROPS[i + 1];
        }
        if (!isAutoCrop()) {
            applyCrop(crop);
        }
    }

    private boolean loadProfileKey(String key, String value) {
        String[] parts = key.split("\\.");
        if (parts.length != 3) return false;
        CropProfile p = profiles.get(parts[1]);
        if (p == null) return false;
        switch (parts[2]) {
            case "yaw" -> p.yaw = Float.parseFloat(value);
            case "pitch" -> p.pitch = Math.max(-90f, Math.min(90f, Float.parseFloat(value)));
            case "slot" -> p.slot = clamp(Integer.parseInt(value), 0, 8);
            case "holdForward" -> p.holdForward = Boolean.parseBoolean(value);
            case "startRight" -> p.startRight = Boolean.parseBoolean(value);
            default -> { return false; }
        }
        return true;
    }

    // --- Pests ---
    public boolean pestsEnabled = true;
    public boolean plotTeleport = true;     // se téléporter sur le plot indiqué par le chat
    public String farmPlot = "";            // numéro du plot où tu farmes (pour y retourner), ex: 3
    public String plotTpCommand = "plottp %s"; // commande sans le "/" ; %s = numéro du plot
    public int teleportDelayTicks = 60;     // attente après une téléportation
    public String pestChatRegex = "(?i)(?:spawned|appeared) in (?:the )?plot\\s*-?\\s*(\\w+)||(?i)pests?.*plot\\s*-?\\s*(\\w+)";
    public boolean pestTabEnabled = true;   // lire le nombre de pests vivants dans le tab
    public int pestTabThreshold = 4;        // nombre de pests (tab) à partir duquel on les chasse (au démarrage, ou après le passage de farm)
    public String pestTabRegex = "(?i)alive:?\\s*(\\d+)"; // ligne du tab contenant le nombre de pests vivants
    public String pestPlotsRegex = "(?i)plots?:\\s*(\\d+(?:\\s*,\\s*\\d+)*)"; // ligne du tab avec les plots infestés, ex: Plots: 2, 3, 4
    public int pestThreshold = 4;       // nombre de pests détectés avant de partir les chasser
    public boolean pestHuntUntilZero = true; // une fois la chasse lancée, continuer jusqu'à ce que le tab indique 0 pest
    public int pestMaxRounds = 6;       // relances max si le tab indique encore des pests (sécurité anti-boucle)
    public boolean plotMustClear = true; // ne pas quitter un plot tant que le tab indique qu'il reste des pests dedans
    public int plotMaxSeconds = 120;    // temps max passé sur un même plot avant de l'abandonner (sécurité)
    public boolean pestSearch = true;   // si aucun pest n'est trouvé alors qu'il en reste : se balader dans le plot pour les chercher
    public boolean pestTrail = true;    // clic gauche avec l'aspirateur : suit la traînée de particules vers le pest le plus proche
    public int trailClickTicks = 30;    // ticks entre deux clics gauches (recherche de la traînée)
    public String trailParticle = "";   // id de la particule de la traînée (vide = détection auto, voir /farmassist trail)
    public int pestSettleTicks = 40;    // attente (ticks) après l'arrivée sur un plot : laisse les pests se charger avant de chercher
    public double pestSweepSpacing = 28; // écart (blocs) entre les passes du balayage systématique du plot
    public String plotLayout = "21,13,9,14,22/15,5,1,6,16/10,2,0,3,11/17,7,4,8,18/23,19,12,20,24"; // grille des plots (0 = grange au centre) ; vide = désactivé
    public double pestSearchRadius = 30; // rayon (blocs) de la balade autour de l'arrivée sur le plot
    public boolean plotLimit = true;    // reste dans le plot où on chasse (cibles, balade et vol)
    public double plotSize = 96;        // taille d'un plot du Garden
    public double plotOffsetX = -48;    // x d'un bord de plot (la grille se répète tous les plotSize) ; voir /farmassist plotcenter
    public double plotOffsetZ = -48;    // z d'un bord de plot
    public double plotMargin = 2;       // marge (blocs) gardée par rapport au bord du plot
    public int vacuumSlot = 1;          // slot hotbar de l'aspirateur / outil anti-pest
    public double pestScanRadius = 300; // rayon de détection (blocs)
    public double pestUseRange = 10;    // distance à laquelle on s'arrête et on utilise l'outil
    public int pestTimeoutSeconds = 30; // abandon d'une chasse trop longue
    public boolean pestFly = true;      // vol vanilla (touches) jusqu'au pest au lieu de marcher
    public double pestCloseDist = 3.0;   // distance (blocs) à laquelle on se colle au pest avant d'aspirer (au plus la portée de l'aspirateur)
    public boolean pestReturnToStart = true; // pests finis : se téléporter au point de début (/farmassist setstart) et reprendre le farm de là
    public double pestFlyStopDist = 2.0; // marge retirée à la portée de l'aspirateur : on s'arrête à (portée - marge), min 3 blocs
    public String pestTypes = "";           // ids d'entités séparés par des virgules (voir /farmassist scan)
    public String pestNameContains = DEFAULT_PEST_NAMES; // noms de pests (séparés par des virgules) cherchés dans le nom de l'entité

    public java.util.List<java.util.regex.Pattern> pestNamePatterns() {
        java.util.List<java.util.regex.Pattern> list = new java.util.ArrayList<>();
        for (String n : pestNameContains.split(",")) {
            n = n.trim();
            if (!n.isEmpty()) {
                // le nom doit apparaître en entier (évite que "Rat" corresponde à "Pirate")
                list.add(java.util.regex.Pattern.compile("(?<![A-Za-z])" + java.util.regex.Pattern.quote(n) + "(?![A-Za-z])"));
            }
        }
        return list;
    }

    public Set<String> pestTypeSet() {
        Set<String> set = new HashSet<>();
        Arrays.stream(pestTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        return set;
    }

    public void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
            boolean foundProfiles = false;
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("crop.")) {
                    foundProfiles |= loadProfileKey(key, p.getProperty(key));
                } else {
                    set(key, p.getProperty(key));
                }
            }
            if (!foundProfiles) {
                // Ancienne config sans profils : on garde ton angle actuel pour la culture active
                saveActiveToProfile();
            }
            // Ancienne config : on remet les nouveaux réglages de détection des pests
            String ver = p.getProperty("configVersion");
            if (ver == null || Integer.parseInt(ver.trim()) < CONFIG_VERSION) {
                pestTypes = "";
                pestNameContains = DEFAULT_PEST_NAMES;
                pestScanRadius = 300;
                save();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[FarmAssist] Config illisible, valeurs par défaut utilisées: " + e);
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("configVersion", String.valueOf(CONFIG_VERSION));
        for (String key : keys()) {
            p.setProperty(key, get(key));
        }
        for (Map.Entry<String, CropProfile> e : profiles.entrySet()) {
            String base = "crop." + e.getKey() + ".";
            CropProfile cp = e.getValue();
            p.setProperty(base + "yaw", String.valueOf(cp.yaw));
            p.setProperty(base + "pitch", String.valueOf(cp.pitch));
            p.setProperty(base + "slot", String.valueOf(cp.slot));
            p.setProperty(base + "holdForward", String.valueOf(cp.holdForward));
            p.setProperty(base + "startRight", String.valueOf(cp.startRight));
        }
        try (Writer w = Files.newBufferedWriter(FILE)) {
            p.store(w, "FarmAssist");
        } catch (IOException e) {
            System.err.println("[FarmAssist] Impossible d'écrire la config: " + e);
        }
    }

    public static String[] keys() {
        return new String[]{"farmSlot", "farmYaw", "farmPitch", "startPoint", "endPoint", "endRadius", "startTpCommand", "loopTpDelayTicks", "farmWhileChatOpen", "holdForward", "startRight", "stuckTicks", "rotationSpeed",
                "pestsEnabled", "pestTabEnabled", "pestTabThreshold", "pestTabRegex", "pestPlotsRegex", "plotTeleport", "farmPlot", "plotTpCommand", "teleportDelayTicks", "pestChatRegex", "pestThreshold", "pestHuntUntilZero", "pestMaxRounds", "plotMustClear", "plotMaxSeconds", "pestSearch", "pestTrail", "trailClickTicks", "trailParticle", "pestSettleTicks", "pestSweepSpacing", "plotLayout", "pestSearchRadius", "plotLimit", "plotSize", "plotOffsetX", "plotOffsetZ", "plotMargin", "vacuumSlot", "pestScanRadius", "pestUseRange", "pestTimeoutSeconds", "pestFly", "pestFlyStopDist", "pestCloseDist", "pestReturnToStart",
                "pestTypes", "pestNameContains", "crop", "activeCrop"};
    }

    public String get(String key) {
        return switch (key) {
            case "farmSlot" -> String.valueOf(farmSlot);
            case "farmYaw" -> String.valueOf(farmYaw);
            case "farmPitch" -> String.valueOf(farmPitch);
            case "startPoint" -> startPoint;
            case "endPoint" -> endPoint;
            case "endRadius" -> String.valueOf(endRadius);
            case "startTpCommand" -> startTpCommand;
            case "loopTpDelayTicks" -> String.valueOf(loopTpDelayTicks);
            case "farmWhileChatOpen" -> String.valueOf(farmWhileChatOpen);
            case "holdForward" -> String.valueOf(holdForward);
            case "startRight" -> String.valueOf(startRight);
            case "stuckTicks" -> String.valueOf(stuckTicks);
            case "rotationSpeed" -> String.valueOf(rotationSpeed);
            case "pestsEnabled" -> String.valueOf(pestsEnabled);
            case "pestTabEnabled" -> String.valueOf(pestTabEnabled);
            case "pestTabThreshold" -> String.valueOf(pestTabThreshold);
            case "pestTabRegex" -> pestTabRegex;
            case "pestPlotsRegex" -> pestPlotsRegex;
            case "plotTeleport" -> String.valueOf(plotTeleport);
            case "farmPlot" -> farmPlot;
            case "plotTpCommand" -> plotTpCommand;
            case "teleportDelayTicks" -> String.valueOf(teleportDelayTicks);
            case "pestChatRegex" -> pestChatRegex;
            case "pestThreshold" -> String.valueOf(pestThreshold);
            case "pestHuntUntilZero" -> String.valueOf(pestHuntUntilZero);
            case "pestMaxRounds" -> String.valueOf(pestMaxRounds);
            case "plotMustClear" -> String.valueOf(plotMustClear);
            case "plotMaxSeconds" -> String.valueOf(plotMaxSeconds);
            case "pestSearch" -> String.valueOf(pestSearch);
            case "pestSearchRadius" -> String.valueOf(pestSearchRadius);
            case "pestSettleTicks" -> String.valueOf(pestSettleTicks);
            case "pestTrail" -> String.valueOf(pestTrail);
            case "trailClickTicks" -> String.valueOf(trailClickTicks);
            case "trailParticle" -> trailParticle;
            case "pestSweepSpacing" -> String.valueOf(pestSweepSpacing);
            case "plotLayout" -> plotLayout;
            case "plotLimit" -> String.valueOf(plotLimit);
            case "plotSize" -> String.valueOf(plotSize);
            case "plotOffsetX" -> String.valueOf(plotOffsetX);
            case "plotOffsetZ" -> String.valueOf(plotOffsetZ);
            case "plotMargin" -> String.valueOf(plotMargin);
            case "vacuumSlot" -> String.valueOf(vacuumSlot);
            case "pestScanRadius" -> String.valueOf(pestScanRadius);
            case "pestUseRange" -> String.valueOf(pestUseRange);
            case "pestTimeoutSeconds" -> String.valueOf(pestTimeoutSeconds);
            case "pestFly" -> String.valueOf(pestFly);
            case "pestFlyStopDist" -> String.valueOf(pestFlyStopDist);
            case "pestCloseDist" -> String.valueOf(pestCloseDist);
            case "pestReturnToStart" -> String.valueOf(pestReturnToStart);
            case "pestTypes" -> pestTypes;
            case "pestNameContains" -> pestNameContains;
            case "crop" -> crop;
            case "activeCrop" -> activeCrop;
            default -> null;
        };
    }

    /** @return false si la clé est inconnue. Lance une exception si la valeur est invalide. */
    public boolean set(String key, String v) {
        switch (key) {
            case "farmSlot" -> farmSlot = clamp(Integer.parseInt(v), 0, 8);
            case "farmYaw" -> farmYaw = Float.parseFloat(v);
            case "farmPitch" -> farmPitch = Math.max(-90f, Math.min(90f, Float.parseFloat(v)));
            case "startPoint" -> startPoint = v.trim();
            case "endPoint" -> endPoint = v.trim();
            case "endRadius" -> endRadius = Math.max(0.5, Double.parseDouble(v));
            case "startTpCommand" -> startTpCommand = v.trim().startsWith("/") ? v.trim().substring(1) : v.trim();
            case "loopTpDelayTicks" -> loopTpDelayTicks = Math.max(5, Integer.parseInt(v));
            case "farmWhileChatOpen" -> farmWhileChatOpen = Boolean.parseBoolean(v);
            case "holdForward" -> holdForward = Boolean.parseBoolean(v);
            case "startRight" -> startRight = Boolean.parseBoolean(v);
            case "stuckTicks" -> stuckTicks = Math.max(2, Integer.parseInt(v));
            case "rotationSpeed" -> rotationSpeed = Math.max(1f, Float.parseFloat(v));
            case "pestsEnabled" -> pestsEnabled = Boolean.parseBoolean(v);
            case "pestTabEnabled" -> pestTabEnabled = Boolean.parseBoolean(v);
            case "pestTabThreshold" -> pestTabThreshold = Math.max(1, Integer.parseInt(v));
            case "pestTabRegex" -> pestTabRegex = v;
            case "pestPlotsRegex" -> pestPlotsRegex = v;
            case "plotTeleport" -> plotTeleport = Boolean.parseBoolean(v);
            case "farmPlot" -> farmPlot = v.trim();
            case "plotTpCommand" -> plotTpCommand = v.trim().startsWith("/") ? v.trim().substring(1) : v.trim();
            case "teleportDelayTicks" -> teleportDelayTicks = Math.max(10, Integer.parseInt(v));
            case "pestChatRegex" -> pestChatRegex = v;
            case "pestThreshold" -> pestThreshold = Math.max(1, Integer.parseInt(v));
            case "pestHuntUntilZero" -> pestHuntUntilZero = Boolean.parseBoolean(v);
            case "pestMaxRounds" -> pestMaxRounds = Math.max(0, Integer.parseInt(v));
            case "plotMustClear" -> plotMustClear = Boolean.parseBoolean(v);
            case "plotMaxSeconds" -> plotMaxSeconds = Math.max(10, Integer.parseInt(v));
            case "pestSearch" -> pestSearch = Boolean.parseBoolean(v);
            case "pestSearchRadius" -> pestSearchRadius = Math.max(3, Double.parseDouble(v));
            case "pestSettleTicks" -> pestSettleTicks = Math.max(0, Integer.parseInt(v));
            case "pestTrail" -> pestTrail = Boolean.parseBoolean(v);
            case "trailClickTicks" -> trailClickTicks = Math.max(12, Integer.parseInt(v));
            case "trailParticle" -> trailParticle = v.trim();
            case "pestSweepSpacing" -> pestSweepSpacing = Math.max(8, Double.parseDouble(v));
            case "plotLayout" -> plotLayout = v.trim();
            case "plotLimit" -> plotLimit = Boolean.parseBoolean(v);
            case "plotSize" -> plotSize = Math.max(16, Double.parseDouble(v));
            case "plotOffsetX" -> plotOffsetX = Double.parseDouble(v);
            case "plotOffsetZ" -> plotOffsetZ = Double.parseDouble(v);
            case "plotMargin" -> plotMargin = Math.max(0, Double.parseDouble(v));
            case "vacuumSlot" -> vacuumSlot = clamp(Integer.parseInt(v), 0, 8);
            case "pestScanRadius" -> pestScanRadius = Math.max(1, Double.parseDouble(v));
            case "pestUseRange" -> pestUseRange = Math.max(1, Double.parseDouble(v));
            case "pestTimeoutSeconds" -> pestTimeoutSeconds = Math.max(5, Integer.parseInt(v));
            case "pestFly" -> pestFly = Boolean.parseBoolean(v);
            case "pestFlyStopDist" -> pestFlyStopDist = Math.max(0.5, Double.parseDouble(v));
            case "pestCloseDist" -> pestCloseDist = Math.max(1.5, Double.parseDouble(v));
            case "pestReturnToStart" -> pestReturnToStart = Boolean.parseBoolean(v);
            case "pestTypes" -> pestTypes = v;
            case "pestNameContains" -> pestNameContains = v;
            case "crop" -> crop = (v.trim().equalsIgnoreCase("AUTO") || profiles.containsKey(v.trim().toUpperCase())) ? v.trim().toUpperCase() : crop;
            case "activeCrop" -> activeCrop = profiles.containsKey(v.trim().toUpperCase()) ? v.trim().toUpperCase() : activeCrop;
            default -> {
                return false;
            }
        }
        return true;
    }

    // --- Points de début / fin ---
    public void setStart(double x, double y, double z, float yaw, float pitch) {
        startPoint = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.2f,%.2f", x, y, z, yaw, pitch);
    }

    public void setEnd(double x, double y, double z) {
        endPoint = String.format(Locale.ROOT, "%.3f,%.3f,%.3f", x, y, z);
    }

    public void clearPoints() {
        startPoint = "";
        endPoint = "";
    }

    public double[] start() { return parsePoint(startPoint, 5); }

    public double[] end() { return parsePoint(endPoint, 3); }

    private static double[] parsePoint(String s, int n) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        if (parts.length < n) return null;
        try {
            double[] r = new double[n];
            for (int i = 0; i < n; i++) r[i] = Double.parseDouble(parts[i].trim());
            return r;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
