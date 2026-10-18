package dev.farmassist;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mémorise les particules reçues du serveur (rempli par le mixin ClientPacketListenerMixin).
 * Le clic gauche avec l'aspirateur fait apparaître une traînée de particules vers le pest le plus proche :
 * aim() renvoie le point le plus loin de cette traînée, donc la direction à suivre.
 */
public final class TrailTracker {
    private record Rec(int tick, String type, double x, double y, double z) { }

    private static final List<Rec> RECS = new ArrayList<>();
    public static volatile int tick = 0; // mis à jour par la macro à chaque tick

    private TrailTracker() { }

    public static synchronized void add(String type, double x, double y, double z) {
        RECS.add(new Rec(tick, type, x, y, z));
        if (RECS.size() > 4000) RECS.subList(0, 2000).clear();
    }

    /** Oublie les particules reçues avant ce tick (on vient de cliquer : on ne veut que la nouvelle traînée). */
    public static synchronized void begin(int fromTick) {
        RECS.removeIf(r -> r.tick < fromTick - 1);
    }

    /**
     * Point de la traînée le plus éloigné de {@code origin}, ou null s'il n'y a pas de traînée reconnue.
     * {@code forcedType} : id de particule imposé (ex: "happy_villager") ; vide = on prend le type le plus fréquent.
     */
    public static synchronized Vec3 aim(int fromTick, Vec3 origin, String forcedType) {
        Map<String, List<Rec>> byType = new HashMap<>();
        for (Rec r : RECS) {
            if (r.tick < fromTick) continue;
            if (forcedType != null && !forcedType.isBlank() && !r.type.equals(forcedType.trim())) continue;
            byType.computeIfAbsent(r.type, k -> new ArrayList<>()).add(r);
        }
        List<Rec> best = null;
        for (List<Rec> l : byType.values()) {
            if (l.size() >= 3 && (best == null || l.size() > best.size())) best = l;
        }
        if (best == null) return null;
        Rec far = null;
        double fd = 0;
        for (Rec r : best) {
            double d = origin.distanceToSqr(r.x, r.y, r.z);
            if (d > fd) { fd = d; far = r; }
        }
        return far == null || Math.sqrt(fd) < 3.0 ? null : new Vec3(far.x, far.y, far.z);
    }

    /** Diagnostic : types de particules vus depuis ce tick, avec leur nombre. */
    public static synchronized String summary(int fromTick) {
        Map<String, Integer> m = new HashMap<>();
        for (Rec r : RECS) if (r.tick >= fromTick) m.merge(r.type, 1, Integer::sum);
        return m.toString();
    }
}
