package dev.gxlg.autoenchanter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class DataStructures {
    public record IterItem<S>(long index, long total, S current) {
    }

    public record Shape(Shape left, Shape right, int leafs) {
        public boolean isLeaf() {
            return left == null || right == null;
        }

        public static Shape leaf() {
            return new Shape(null, null, 1);
        }

        public List<Leaf> getLeafs() {
            List<Leaf> list = new ArrayList<>();
            leafs(0, 0, list);
            return list;
        }

        private void leafs(int depth, int cost, List<Leaf> list) {
            if (isLeaf()) {
                list.add(new Leaf(depth, cost, this));
            } else {
                left.leafs(depth + 1, cost, list);
                right.leafs(depth + 1, cost + 1, list);
            }
        }

        public FilledShape fill(List<Enchant> filler) {
            List<Enchant> copy = new ArrayList<>(filler);
            return fillInternal(copy);
        }

        private FilledShape fillInternal(List<Enchant> list) {
            if (isLeaf()) return FilledShape.leaf(list.removeFirst());
            return new FilledShape(left.fillInternal(list), right.fillInternal(list));
        }

        public Stream<List<Enchant>> possibleFills(List<Enchant> alreadyFilled, Map<Integer, List<Enchant>> map, Holder<Enchantment> current, Enchant main) {
            List<Enchant> allItems = map.values().stream().flatMap(List::stream).toList();
            return possibleFillsInternal(alreadyFilled, MergeTree.from(map), 0, allItems, current, main);
        }

        private Stream<List<Enchant>> possibleFillsInternal(List<Enchant> alreadyFilled, MergeTree tree, int indexOffset, List<Enchant> items, Holder<Enchantment> currentEnchantment, Enchant main) {
            if (tree.value() == 0) {
                return Stream.of(alreadyFilled.subList(indexOffset, indexOffset + leafs()));
            }

            if (isLeaf()) {
                if (tree.hasChildren()) return Stream.empty();
                if (indexOffset == 0) {
                    Enchant m = items.stream().filter(i -> i == main).findFirst().orElse(null);
                    if (m == null) return Stream.empty();
                    return Stream.of(List.of(m));
                }

                if (alreadyFilled.get(indexOffset) != null) {
                    if (!items.contains(alreadyFilled.get(indexOffset))) return Stream.empty();
                    return Stream.of(List.of(alreadyFilled.get(indexOffset)));
                }

                return items.stream().filter(i -> i != main && i.enchantments().containsKey(currentEnchantment) && i.enchantments().get(currentEnchantment).lvl() == tree.value()).map(List::of);
            }

            List<Map.Entry<MergeTree, MergeTree>> splits = new ArrayList<>();
            if (tree.hasChildren()) {
                splits.add(Map.entry(tree.left(), tree.right()));
                if (!tree.left().same(tree.right())) splits.add(Map.entry(tree.right(), tree.left()));
            }
            splits.add(Map.entry(tree, MergeTree.leaf(0)));
            splits.add(Map.entry(MergeTree.leaf(0), tree));

            return splits.stream().flatMap(split -> left.possibleFillsInternal(alreadyFilled, split.getKey(), indexOffset, items, currentEnchantment, main).flatMap(ll -> right.possibleFillsInternal(alreadyFilled, split.getValue(), indexOffset + left.leafs(), items.stream().filter(i -> ll.stream().noneMatch(j -> i == j)).toList(), currentEnchantment, main).map(rr -> Stream.concat(ll.stream(), rr.stream()).toList())));
        }

        public void draw(GuiGraphicsExtractor context, Font textRenderer, int x, int y, int w, int h) {
            draw(context, textRenderer, x, y, w, h, 0, 0);
        }

        @SuppressWarnings("unused")
        private void draw(GuiGraphicsExtractor context, Font textRenderer, int x, int y, int w, int h, int depth, int cost) {
            int color = 0xF2DCA0 + (0x6D12A6 - 0xF2DCA0) * depth / 6;
            int base = 0xFF000000;
            context.fill(x, y, x + w, y + h, base + color / 2);
            context.fill(x + 1, y + 1, x + w - 1, y + h - 1, base + color);

            if (isLeaf()) {
                context.text(textRenderer, String.valueOf(cost), x + 3, y + 3, 0xFF000000, false);
                return;
            }

            if (h > w) {
                left.draw(context, textRenderer, x, y, w, h / 2, depth + 1, cost);
                right.draw(context, textRenderer, x, y + h / 2, w, h / 2, depth + 1, cost + 1);
            } else {
                left.draw(context, textRenderer, x, y, w / 2, h, depth + 1, cost);
                right.draw(context, textRenderer, x + w / 2, y, w / 2, h, depth + 1, cost + 1);
            }
        }
    }

    public record Leaf(int depth, int cost, Shape shape) {
    }

    public record MergeTree(MergeTree left, MergeTree right, int value) {
        public static MergeTree leaf(int value) {
            return new MergeTree(null, null, value);
        }

        public boolean hasChildren() {
            return left != null && right != null;
        }

        public boolean same(MergeTree tree) {
            if (value != tree.value()) return false;
            if (hasChildren() != tree.hasChildren()) return false;
            if (hasChildren()) return left.same(tree.left()) && right.same(tree.right());
            else return true;
        }

        public static MergeTree from(Map<Integer, List<Enchant>> map) {
            List<MergeTree> stack = new ArrayList<>();
            for (Integer i : map.entrySet().stream().flatMap(i -> i.getValue().stream().map(x -> i.getKey())).sorted().toList()) {
                stack.add(MergeTree.leaf(i));

                while (stack.size() > 1 && stack.getLast().value() == stack.get(stack.size() - 2).value()) {
                    MergeTree right = stack.removeLast();
                    MergeTree left = stack.removeLast();
                    stack.add(new MergeTree(left, right, left.value() + 1));
                }
            }
            return stack.isEmpty() ? MergeTree.leaf(0) : stack.getLast();
        }
    }

    public record Enchant(Map<Holder<Enchantment>, EMap> enchantments, int anvilUse, Item item) {
        public static Enchant from(ItemStack stack) {
            int repair = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
            int anvilUse = Integer.bitCount(repair);

            ItemEnchantments component = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            Map<Holder<Enchantment>, EMap> enchantments = component.keySet().stream().collect(Collectors.toMap(
                    holder -> holder,
                    holder -> EMap.from(component.getLevel(holder), holder.value().getAnvilCost())
            ));
            return new Enchant(enchantments, anvilUse, stack.getItem());
        }
    }

    public record EnchantedItem(Map<Holder<Enchantment>, EMap> enchantments, int anvilUse, int cost, Item item) {
        public static EnchantedItem INVALID = new EnchantedItem(Collections.emptyMap(), -1, -1, null);

        public boolean matches(ItemStack stack) {
            Enchant citem = Enchant.from(stack);
            return item == citem.item() && anvilUse == citem.anvilUse() && enchantments.equals(citem.enchantments());
        }

    }

    public static class FilledShape {
        private final Enchant item;
        private final FilledShape left;
        private final FilledShape right;

        public FilledShape(FilledShape left, FilledShape right) {
            this.left = left;
            this.right = right;
            this.item = new Enchant(Collections.emptyMap(), 0, null);
        }

        private FilledShape(Enchant item) {
            this.left = null;
            this.right = null;
            this.item = item;
        }

        @Override
        public String toString() {
            return "[" + (isLeaf() ? item : Objects.requireNonNull(left) + ", " + Objects.requireNonNull(right)) + "]";
        }

        public boolean isLeaf() {
            return left == null || right == null;
        }

        public static FilledShape leaf(Enchant item) {
            return new FilledShape(item);
        }

        public List<AnvilItem> execute() {
            assert left != null && right != null;

            if (left.isLeaf() && right.isLeaf()) {
                EnchantedItem l = new EnchantedItem(left.item.enchantments(), left.item.anvilUse(), 0, left.item.item());
                EnchantedItem r = new EnchantedItem(right.item.enchantments(), right.item.anvilUse(), 0, right.item.item());
                return List.of(new AnvilItem(l, r, combine(l, r)));
            }

            List<AnvilItem> l = left.isLeaf() ? Collections.emptyList() : left.execute();
            List<AnvilItem> r = right.isLeaf() ? Collections.emptyList() : right.execute();

            EnchantedItem ll = left.isLeaf() ? new EnchantedItem(left.item.enchantments(), left.item.anvilUse(), 0, left.item.item()) : l.getLast().result;
            EnchantedItem rr = right.isLeaf() ? new EnchantedItem(right.item.enchantments(), right.item.anvilUse(), 0, right.item.item()) : r.getLast().result();

            List<AnvilItem> x = new ArrayList<>();
            if (left.isLeaf() && !right.isLeaf()) {
                x.addAll(r);
                x.addAll(l);
            } else {
                x.addAll(l);
                x.addAll(r);
            }
            x.add(new AnvilItem(ll, rr, combine(ll, rr)));
            return x;
        }

        private static EnchantedItem combine(EnchantedItem l, EnchantedItem r) {
            if (l == EnchantedItem.INVALID || r == EnchantedItem.INVALID) return EnchantedItem.INVALID;
            if (l.item() == Items.ENCHANTED_BOOK && r.item() != Items.ENCHANTED_BOOK)
                return EnchantedItem.INVALID; // can't use an item as a sacrifice if the target is a book

            Map<Holder<Enchantment>, EMap> enchantments = new HashMap<>();
            int c = 0;
            boolean anyValid = false;
            for (Map.Entry<Holder<Enchantment>, EMap> e : r.enchantments().entrySet()) {
                Holder<Enchantment> sacrifice = e.getKey();
                EMap sacrificeData = e.getValue();

                boolean pair = false;
                for (Map.Entry<Holder<Enchantment>, EMap> d : l.enchantments().entrySet()) {
                    Holder<Enchantment> target = d.getKey();
                    EMap targetData = d.getValue();

                    if (target == sacrifice) {
                        pair = true;
                        anyValid = true;
                        int m;
                        if (targetData.lvl() == sacrificeData.lvl()) {
                            if (sacrificeData.lvl() == sacrifice.value().getMaxLevel()) {
                                enchantments.put(sacrifice, sacrificeData);
                                m = sacrificeData.lvl();
                            } else {
                                enchantments.put(sacrifice, EMap.from(sacrificeData.lvl() + 1, sacrificeData.itemCost()));
                                m = sacrificeData.lvl() + 1;
                            }
                        } else {
                            m = Math.max(targetData.lvl(), sacrificeData.lvl());
                            enchantments.put(sacrifice, EMap.from(m, sacrificeData.itemCost()));
                        }
                        c += sacrificeData.cost(r.item()) * m;
                        break;
                    } else if (!MultiVersion.canCombine(target, sacrifice)) {
                        pair = true;
                        c += 1;
                    }
                }
                if (!pair && (sacrifice.value().canEnchant(l.item().getDefaultInstance()) || l.item() == Items.ENCHANTED_BOOK)) {
                    anyValid = true;
                    enchantments.put(sacrifice, sacrificeData);
                    c += sacrificeData.cost(r.item()) * sacrificeData.lvl();
                }
            }
            if (!anyValid) return EnchantedItem.INVALID; // no enchantments were applied

            for (Map.Entry<Holder<Enchantment>, EMap> d : l.enchantments().entrySet()) {
                if (!enchantments.containsKey(d.getKey())) enchantments.put(d.getKey(), d.getValue());
            }

            c += (1 << l.anvilUse()) + (1 << r.anvilUse()) - 2;
            if (c >= 40) return EnchantedItem.INVALID;
            return new EnchantedItem(enchantments, Math.max(l.anvilUse(), r.anvilUse()) + 1, l.cost() + r.cost() + c, l.item());
        }

        public EnchantedItem finalItem() {
            if (isLeaf()) {
                return new EnchantedItem(item.enchantments, item.anvilUse(), 0, item.item());
            }
            assert left != null && right != null;

            EnchantedItem l = left.finalItem();
            EnchantedItem r = right.finalItem();

            return combine(l, r);
        }
    }

    public record EMap(int lvl, int itemCost, int bookCost) {
        public int cost(Item item) {
            return item == Items.ENCHANTED_BOOK ? bookCost : itemCost;
        }

        public static EMap from(int lvl, int itemCost) {
            return new EMap(lvl, itemCost, Math.max(itemCost / 2, 1));
        }
    }

    public record AnvilItem(EnchantedItem target, EnchantedItem sacrifice, EnchantedItem result) {
    }
}
