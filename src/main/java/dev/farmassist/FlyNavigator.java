package dev.farmassist;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pathfinder de vol (A* en 3D) : trouve un chemin qui contourne les blocs entre le joueur et un objectif,
 * puis donne le prochain point à viser. Aucun réseau, aucune écriture de fichier.
 */
public final class FlyNavigator {
    private static final int MAX_NODES = 6000;      // limite de recherche par calcul
    private static final double H_WEIGHT = 1.6;     // >1 = plus rapide, chemin un peu moins parfait
    private static final int REPLAN_TICKS = 30;     // recalcul régulier (la cible bouge)
    private static final int LOOKAHEAD = 8;         // nombre de points regardés pour raccourcir le chemin

    private List<Vec3> path = new ArrayList<>();
    private int idx;
    private int lastPlanTick = -1000;
    private Vec3 lastGoal;
    private final Set<Long> banned = new HashSet<>(); // cases où on s'est coincé

    private double[] bounds = null;   // {minX, maxX, minZ, maxZ} : le pathfinder ne sort jamais de cette zone
    private double boundMargin = 0;

    /** Limite la navigation à un rectangle (le plot). {@code null} = pas de limite. */
    public void setBounds(double[] b, double margin) {
        if (!java.util.Arrays.equals(bounds, b)) lastPlanTick = -1000; // nouvelle zone : on recalcule le chemin
        bounds = b;
        boundMargin = margin;
    }

    public void clear() {
        path = new ArrayList<>();
        idx = 0;
        lastPlanTick = -1000;
        lastGoal = null;
        banned.clear();
    }

    /** Marque une case (et celle du dessus) comme infranchissable pour les prochains calculs. */
    public void ban(BlockPos pos) {
        banned.add(pos.asLong());
        banned.add(pos.above().asLong());
        lastPlanTick = -1000; // force un nouveau calcul
    }

    /**
     * Retourne le prochain point à viser pour aller de {@code from} à {@code goal}
     * (on s'arrête quand on est à moins de {@code reach} blocs de l'objectif).
     * Sans chemin trouvé, retourne l'objectif lui-même (ligne droite).
     */
    public Vec3 waypoint(Level level, Vec3 from, Vec3 goal, double reach, int tick) {
        // Ligne droite dégagée jusqu'à (presque) l'objectif : on y va direct, sans A* (beaucoup plus rapide, pas de saccade)
        Vec3 toGoal = goal.subtract(from);
        double len = toGoal.length();
        if (len > 0.01) {
            double keep = Math.max(0.0, len - reach * 0.7);
            Vec3 end = from.add(toGoal.scale(keep / len));
            if (lineClear(level, from, end)) {
                path = new ArrayList<>();
                idx = 0;
                lastGoal = null;
                return goal;
            }
        }
        boolean goalMoved = lastGoal == null || lastGoal.distanceToSqr(goal) > 36.0;
        if (path.isEmpty() || goalMoved || tick - lastPlanTick >= REPLAN_TICKS) {
            plan(level, from, goal, reach, tick);
        }
        if (path.isEmpty()) return goal;

        // Avance dans le chemin quand on est assez près du point visé
        while (idx < path.size() - 1 && from.distanceTo(path.get(idx)) < 0.9) idx++;

        // Raccourci : vise le point le plus loin (parmi les suivants) qu'on voit en ligne droite
        for (int i = Math.min(path.size() - 1, idx + LOOKAHEAD); i > idx; i--) {
            if (lineClear(level, from, path.get(i))) {
                idx = i;
                break;
            }
        }
        return path.get(idx);
    }

    // ------------------------------------------------------------ A*

    private static final class Node {
        final BlockPos pos;
        final double g;
        final double f;
        final Node parent;

        Node(BlockPos pos, double g, double f, Node parent) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }
    }

    private void plan(Level level, Vec3 from, Vec3 goal, double reach, int tick) {
        lastPlanTick = tick;
        lastGoal = goal;
        idx = 0;
        BlockPos start = BlockPos.containing(from.x, from.y + 0.1, from.z);
        BlockPos end = BlockPos.containing(goal);

        // Limite verticale de la recherche (évite de chercher à l'infini vers le haut/bas)
        int minY = Math.min(start.getY(), end.getY()) - 20;
        int maxY = Math.max(start.getY(), end.getY()) + 40;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        HashMap<Long, Node> best = new HashMap<>();
        Node first = new Node(start, 0, H_WEIGHT * dist(start, end), null);
        open.add(first);
        best.put(start.asLong(), first);

        Node closest = first;
        double closestD = dist(start, end);
        int expanded = 0;
        boolean reached = false;
        Node last = null;

        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node n = open.poll();
            if (n != best.get(n.pos.asLong())) continue; // entrée périmée
            expanded++;

            double d = dist(n.pos, end);
            if (d <= reach) {
                last = n;
                reached = true;
                break;
            }
            if (d < closestD) {
                closestD = d;
                closest = n;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos np = n.pos.offset(dx, dy, dz);
                        if (np.getY() < minY || np.getY() > maxY) continue;
                        if (!passable(level, np)) continue;
                        // Pas de passage en diagonale à travers un coin plein
                        if (dx != 0 && !passable(level, n.pos.offset(dx, 0, 0))) continue;
                        if (dy != 0 && !passable(level, n.pos.offset(0, dy, 0))) continue;
                        if (dz != 0 && !passable(level, n.pos.offset(0, 0, dz))) continue;

                        double ng = n.g + Math.sqrt(dx * dx + dy * dy + dz * dz);
                        Node ex = best.get(np.asLong());
                        if (ex != null && ex.g <= ng) continue;
                        Node nn = new Node(np, ng, ng + H_WEIGHT * dist(np, end), n);
                        best.put(np.asLong(), nn);
                        open.add(nn);
                    }
                }
            }
        }

        Node target = reached ? last : closest; // sinon on suit le chemin partiel le plus proche
        List<Vec3> out = new ArrayList<>();
        for (Node n = target; n != null; n = n.parent) {
            out.add(new Vec3(n.pos.getX() + 0.5, n.pos.getY(), n.pos.getZ() + 0.5));
        }
        Collections.reverse(out);
        if (!out.isEmpty()) out.remove(0); // le point de départ
        if (reached) out.add(goal);        // dernier point = l'objectif exact
        path = out;
    }

    // ------------------------------------------------------------ Collisions

    private boolean passable(Level level, BlockPos p) {
        if (banned.contains(p.asLong())) return false;
        if (bounds != null) {
            double cx = p.getX() + 0.5, cz = p.getZ() + 0.5;
            if (cx < bounds[0] + boundMargin || cx > bounds[1] - boundMargin
                    || cz < bounds[2] + boundMargin || cz > bounds[3] - boundMargin) return false;
        }
        return freeCell(level, p) && freeCell(level, p.above()); // le joueur fait ~1,8 bloc de haut
    }

    private static boolean freeCell(Level level, BlockPos p) {
        if (!level.hasChunkAt(p)) return false; // chunk non chargé = on n'y va pas
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Vrai si on peut aller en ligne droite de a à b (on teste le centre et 4 points autour : ~0,6 de large). */
    private boolean lineClear(Level level, Vec3 a, Vec3 b) {
        Vec3 delta = b.subtract(a);
        double len = delta.length();
        if (len < 0.01) return true;
        int steps = (int) Math.ceil(len / 0.5);
        for (int i = 1; i <= steps; i++) {
            Vec3 pt = a.add(delta.scale(i / (double) steps));
            if (!passable(level, BlockPos.containing(pt.x, pt.y + 0.1, pt.z))) return false;
            if (!passable(level, BlockPos.containing(pt.x + 0.3, pt.y + 0.1, pt.z))) return false;
            if (!passable(level, BlockPos.containing(pt.x - 0.3, pt.y + 0.1, pt.z))) return false;
            if (!passable(level, BlockPos.containing(pt.x, pt.y + 0.1, pt.z + 0.3))) return false;
            if (!passable(level, BlockPos.containing(pt.x, pt.y + 0.1, pt.z - 0.3))) return false;
        }
        return true;
    }

    private static double dist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
