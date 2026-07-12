package dev.gxlg.autoenchanter;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;

import static dev.gxlg.autoenchanter.DataStructures.*;

import dev.gxlg.autoenchanter.DataStructures.Enchant;
import dev.gxlg.autoenchanter.DataStructures.EnchantedItem;
import dev.gxlg.autoenchanter.DataStructures.FilledShape;
import dev.gxlg.autoenchanter.DataStructures.IterItem;
import dev.gxlg.autoenchanter.DataStructures.Leaf;
import dev.gxlg.autoenchanter.DataStructures.Shape;

public class Utils {
    private static BigInteger countWithDepth(int amount, int depth, int maxDepth, Map<Long, BigInteger> memo) {
        long key = ((long) amount << 32) | (depth & 0xffffffffL);
        if (memo.containsKey(key)) return memo.get(key);
        if (depth > maxDepth) {
            memo.put(key, BigInteger.ZERO);
            return BigInteger.ZERO;
        }
        if (amount == 1) {
            memo.put(key, BigInteger.ONE);
            return BigInteger.ONE;
        }
        BigInteger sum = BigInteger.ZERO;
        for (int l = 1; l <= amount - 1; l++) {
            int r = amount - l;
            BigInteger leftCount = countWithDepth(l, depth + 1, maxDepth, memo);
            if (leftCount.equals(BigInteger.ZERO)) continue;
            BigInteger rightCount = countWithDepth(r, depth + 1, maxDepth, memo);
            if (rightCount.equals(BigInteger.ZERO)) continue;
            sum = sum.add(leftCount.multiply(rightCount));
        }
        memo.put(key, sum);
        return sum;
    }

    public static Stream<IterItem<Shape>> genShapes(int amount) {
        return genShapesInternal(amount, 0, true);
    }

    private static Stream<IterItem<Shape>> genShapesInternal(int amount, int depth, boolean count) {
        if (depth > 6) return Stream.empty();
        if (amount == 1) return Stream.of(new IterItem<>(0, 0, Shape.leaf()));

        long total = count ? countWithDepth(amount, 0, 6, new HashMap<>()).longValue() : 0;
        AtomicLong p = new AtomicLong(0);

        return IntStream.range(0, amount).boxed().flatMap(l -> {
            int r = amount - l;
            return genShapesInternal(l, depth + 1, false).flatMap(ileft -> genShapesInternal(r, depth + 1, false).map(iright -> {
                Shape left = ileft.current();
                Shape right = iright.current();
                return new IterItem<>(p.addAndGet(1), total, new Shape(left, right, left.leafs() + right.leafs()));
            }));
        });
    }

    private static Stream<List<Enchant>> fills(Shape tree, List<Enchant> filled, Map<Holder<Enchantment>, Map<Integer, List<Enchant>>> map, Enchant main, Set<Holder<Enchantment>> ignoredEncs) {
        if (map.size() == 0) return Stream.of(filled);
        Map<Holder<Enchantment>, Map<Integer, List<Enchant>>> m = new HashMap<>(map);
        Holder<Enchantment> first;
        do {
            first = m.keySet().stream().findFirst().orElseThrow();
            m.remove(first);
        } while (ignoredEncs.contains(first));
        return tree.possibleFills(filled, map.get(first), first, main).flatMap(f -> fills(tree, f, m, main, ignoredEncs));
    }

    public static class ShapePool {
        private final AtomicReference<IterItem<Shape>> current = new AtomicReference<>();
        private final AtomicReference<Map.Entry<FilledShape, Integer>> bestShape = new AtomicReference<>();
        private final int amount;
        private final Enchant mainItem;
        private final Map<Holder<Enchantment>, Map<Integer, List<Enchant>>> trueMap;
        private final List<Enchant> collection;
        private final ExecutorService pool;
        private final int threads;
        private final BlockingQueue<IterItem<Shape>> queue;
        private final Set<Holder<Enchantment>> ignoredEncs;
        private final Map<Holder<Enchantment>, Integer> maxMap;
        private volatile boolean submitting = true;
        private final AtomicInteger finished = new AtomicInteger(0);

        public ShapePool(int amount, List<Enchant> collection, Map<Holder<Enchantment>, Map<Integer, List<Enchant>>> trueMap, Enchant mainItem, Set<Holder<Enchantment>> ignoredEncs, Map<Holder<Enchantment>, Integer> maxMap) {
            this.amount = amount;
            this.collection = collection;
            this.trueMap = trueMap;
            this.mainItem = mainItem;
            this.ignoredEncs = ignoredEncs;
            this.maxMap = maxMap;

            threads = Runtime.getRuntime().availableProcessors();
            pool = Executors.newFixedThreadPool(threads);
            queue = new ArrayBlockingQueue<>(4096);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        while (true) {
                            IterItem<Shape> shape = queue.poll(100, TimeUnit.MILLISECONDS);
                            if (shape == null) {
                                if (submitting) continue;
                                else break;
                            }
                            current.set(shape);
                            List<Leaf> leafs = shape.current().getLeafs();
                            processShape(shape, leafs);
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } finally {
                        finished.updateAndGet(j -> j + 1);
                    }
                });
            }

            new Thread(() -> {
                Iterator<IterItem<Shape>> si = genShapes(amount).iterator();
                while (si.hasNext() && submitting) {
                    try {
                        queue.put(si.next());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                submitting = false;
                pool.shutdown();
                try {
                    boolean ignored = pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        private void processShape(IterItem<Shape> shape, List<Leaf> leafs) {
            Shape s = shape.current();
            List<Enchant> prefilled = IntStream.range(0, amount).mapToObj(i -> i == 0 ? mainItem : null).toList();
            Map.Entry<FilledShape, Integer> m = fills(s, prefilled, trueMap, mainItem, ignoredEncs).map(f -> {
                List<Enchant> filled = new ArrayList<>(f);
                List<Enchant> available = collection.stream().filter(i -> filled.stream().noneMatch(j -> i == j)).toList();

                List<Integer> empty = new ArrayList<>();
                for (int i = 0; i < amount; i++) {
                    if (filled.get(i) == null) empty.add(i);
                }

                empty.sort(Comparator.comparingInt(i -> -leafs.get(i).cost()));
                for (int i = 0; i < available.size(); i++) {
                    filled.set(empty.get(i), available.get(i));
                }

                FilledShape fs = s.fill(filled);
                EnchantedItem i = fs.finalItem();
                if (i == EnchantedItem.INVALID) return Map.entry(fs, -1);
                if (ignoredEncs.stream().anyMatch(e -> i.enchantments().containsKey(e))) return Map.entry(fs, -1);
                if (i.enchantments().size() != maxMap.size() || !i.enchantments().entrySet().stream().allMatch(e -> maxMap.containsKey(e.getKey()) && maxMap.get(e.getKey()) == e.getValue().lvl())) return Map.entry(fs, -1);
                return Map.entry(fs, i.cost());
            }).filter(e -> e.getValue() != -1).min(Comparator.comparingInt(Map.Entry::getValue)).orElse(null);
            if (m == null) return;
            bestShape.updateAndGet(e -> e == null || e.getValue() > m.getValue() ? m : e);
        }

        public IterItem<Shape> peek() {
            return current.get();
        }

        public void cancel() {
            submitting = false;
            pool.shutdownNow();
        }

        public boolean working() {
            return finished.get() < threads;
        }

        public FilledShape getBestShape() {
            return bestShape.get() == null ? null : bestShape.get().getKey();
        }

        public int getMinCost() {
            return bestShape.get() == null ? -1 : bestShape.get().getValue();
        }
    }
}
