package dev.gxlg.autoenchanter;

import com.mojang.brigadier.context.CommandContext;
import dev.gxlg.autoenchanter.DataStructures.AnvilItem;
import dev.gxlg.autoenchanter.DataStructures.EMap;
import dev.gxlg.autoenchanter.DataStructures.Enchant;
import dev.gxlg.autoenchanter.DataStructures.EnchantedItem;
import dev.gxlg.autoenchanter.DataStructures.FilledShape;
import dev.gxlg.autoenchanter.DataStructures.IterItem;
import dev.gxlg.autoenchanter.DataStructures.Shape;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dev.gxlg.autoenchanter.DataStructures.*;

public class Worker {

    private static StringWidget textDisplay;
    private static Button buttonSelect, buttonCalculate, buttonCancel, buttonStart;
    private static State state = State.UNSET;


    private static final List<Integer> selected = new ArrayList<>();

    private static int enchantCost = 0;
    private static boolean readyToEnchant = false;
    private static Utils.ShapePool search = null;
    private static IterItem<Shape> current = null;
    private static List<AnvilItem> operations = null;

    public static void setup(StringWidget text, Button select, Button calculate, Button start, Button cancel) {
        textDisplay = text;
        buttonSelect = select;
        buttonCalculate = calculate;
        buttonStart = start;
        buttonCancel = cancel;

        if (state == State.CALCULATE) {
            showText("Calculating...", Colors.BLUE);
            buttonSelect.visible = false;
            buttonCalculate.visible = false;
            buttonCancel.visible = true;
            buttonStart.visible = false;

        } else if (readyToEnchant) {
            ready();
        } else {
            cancel();
        }
    }

    public static void ready() {
        showText("Can enchant for " + enchantCost + " lvls", Colors.GREEN);
        buttonSelect.visible = false;
        buttonCalculate.visible = false;
        buttonCancel.visible = true;
        buttonStart.visible = true;
        state = State.UNSET;
    }

    public static void select() {
        showText("Select items to use", Colors.YELLOW);
        buttonSelect.visible = false;
        buttonCalculate.visible = true;
        buttonCancel.visible = true;
        buttonStart.visible = false;
        state = State.SELECT;
    }

    public static int cancelCommand(CommandContext<FabricClientCommandSource> context) {
        cancel();
        context.getSource().sendFeedback(Component.nullToEmpty("Cancelled everything"));
        return 0;
    }

    public static void cancel() {
        if (search != null) {
            search.cancel();
            search = null;
        }
        current = null;
        readyToEnchant = false;
        operations = null;
        selected.clear();
        hideText();
        buttonSelect.visible = true;
        buttonCalculate.visible = false;
        buttonCancel.visible = false;
        buttonStart.visible = false;
        state = State.UNSET;
    }

    public static void calculate() {
        if (selected.size() < 2) {
            showText("Not enough items selected", Colors.RED);
            return;
        }

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        AbstractContainerMenu handler = player.containerMenu;
        if (handler == null) return;

        List<Enchant> collection = new ArrayList<>();
        Enchant mainItem = null;

        for (int slot : selected) {
            ItemStack stack = handler.getSlot(slot).getItem();
            Enchant item = Enchant.from(stack);
            collection.add(item);
            if (mainItem == null) mainItem = item;
        }

        boolean conflicts = false;

        Set<Holder<Enchantment>> ignoredEncs = new HashSet<>();
        for (int i = 0; i < collection.size() - 1; i++) {
            Enchant b = collection.get(i);
            if (b.enchantments().size() == 0) continue;

            boolean anyToItem = false;
            for (int j = i + 1; j < collection.size(); j++) {
                Enchant c = collection.get(j);
                for (Holder<Enchantment> d : b.enchantments().keySet()) {
                    boolean anyCompat = false;
                    for (Holder<Enchantment> e : c.enchantments().keySet()) {
                        if (d == e || MultiVersion.canCombine(d, e)) anyCompat = true;
                        if (d != e && !MultiVersion.canCombine(d, e)) {
                            if (b == mainItem) {
                                ignoredEncs.add(e);
                            }
                        }
                    }
                    if (!anyCompat) {
                        conflicts = true;
                        break;
                    }

                    if (d.value().canEnchant(mainItem.item().getDefaultInstance()) || mainItem.item() == Items.ENCHANTED_BOOK)
                        anyToItem = true;
                }
            }
            if (!anyToItem) {
                conflicts = true;
                break;
            }
        }

        if (conflicts) {
            showText("Some items are incompatible or useless", Colors.RED);
            return;
        }

        Map<Holder<Enchantment>, Map<Integer, List<Enchant>>> map = new HashMap<>();
        for (Enchant item : collection) {
            for (Map.Entry<Holder<Enchantment>, EMap> e : item.enchantments().entrySet()) {
                Holder<Enchantment> enc = e.getKey();
                int lvl = e.getValue().lvl();

                if (!map.containsKey(enc)) map.put(enc, new HashMap<>());
                Map<Integer, List<Enchant>> listMap = map.get(enc);
                if (!listMap.containsKey(lvl)) listMap.put(lvl, new ArrayList<>());
                listMap.get(lvl).add(item);
            }
        }

        Map<Holder<Enchantment>, Map<Integer, List<Enchant>>> trueMap = new HashMap<>();
        Map<Holder<Enchantment>, Integer> wasted = new HashMap<>();
        Map<Holder<Enchantment>, Integer> maxMap = new HashMap<>();
        for (Map.Entry<Holder<Enchantment>, Map<Integer, List<Enchant>>> e : map.entrySet()) {
            List<Enchant> en = e.getValue().values().stream().findFirst().orElseThrow();
            if (e.getValue().size() == 1 && en.size() == 1) {
                maxMap.put(e.getKey(), e.getValue().keySet().stream().toList().getFirst());
                continue;
            } else {
                trueMap.put(e.getKey(), e.getValue());
            }

            List<Integer> stack = new ArrayList<>();
            for (int i : e.getValue().entrySet().stream().flatMap(x -> x.getValue().stream().map(z -> x.getKey())).sorted().toList()) {
                stack.add(i);
                while (stack.size() > 1 && stack.getLast().equals(stack.get(stack.size() - 2)) && stack.getLast() < e.getKey().value().getMaxLevel()) {
                    int j = stack.removeLast();
                    stack.removeLast();
                    stack.add(j + 1);
                }
            }
            if (stack.size() > 1 && !ignoredEncs.contains(e.getKey())) {
                if (stack.getLast() == e.getKey().value().getMaxLevel()) {
                    wasted.put(e.getKey(), stack.subList(0, stack.size() - 1).stream().mapToInt(i -> i).min().orElse(0));
                } else {
                    showText(e.getKey().value() + " has wasted items", Colors.RED);
                    return;
                }
            }
            maxMap.put(e.getKey(), stack.getLast());
        }

        if (!ignoredEncs.isEmpty()) {
            for (Enchant enchant : collection) {
                if (ignoredEncs.containsAll(enchant.enchantments().keySet())) {
                    showText("Some items' every enchantment is ignored", Colors.RED);
                    return;
                }
            }
        }

        if (!wasted.isEmpty()) {
            for (Enchant enchant : collection) {
                if (enchant.enchantments().entrySet().stream().allMatch(e -> wasted.containsKey(e.getKey()) && e.getValue().lvl() <= wasted.get(e.getKey()))) {
                    showText("Some items' every enchantment is wasted", Colors.RED);
                    return;
                }
            }
        }

        List<Enchant> allItems = new ArrayList<>(collection.subList(1, collection.size()));
        allItems.sort(Comparator.comparingInt(e -> e.enchantments().values().stream().mapToInt(j -> j.lvl() * j.cost(e.item())).sum()));

        search = new Utils.ShapePool(collection.size(), allItems, trueMap, mainItem, ignoredEncs, maxMap);
        showText("Calculating...", Colors.BLUE);
        buttonSelect.visible = false;
        buttonCalculate.visible = false;
        buttonCancel.visible = true;
        buttonStart.visible = false;
        state = State.CALCULATE;
        opid = 0;
        sopid = 0;
        timer = 0;
    }

    public static void start() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        AbstractContainerMenu handler = player.containerMenu;

        List<EnchantedItem> outputs = operations.subList(opid, operations.size()).stream().map(AnvilItem::result).toList();
        List<EnchantedItem> inputs = operations.subList(opid, operations.size()).stream().flatMap(e -> Stream.of(e.target(), e.sacrifice())).filter(i -> outputs.stream().noneMatch(j -> j == i)).toList();
        Set<Integer> found = new HashSet<>();
        for (EnchantedItem inp : inputs) {
            int slot = IntStream.range(3, handler.getItems().size()).boxed().filter(i -> !found.contains(i) && inp.matches(handler.getSlot(i).getItem())).findFirst().orElse(-1);
            if (slot == -1) {
                showText("Not all items for enchanting are present", Colors.RED);
                return;
            }
            found.add(slot);
        }

        showText("Enchanting, please don't use the mouse...", Colors.GREEN);
        buttonSelect.visible = false;
        buttonCalculate.visible = false;
        buttonCancel.visible = true;
        buttonStart.visible = false;
        state = State.EXEC;
    }

    public static void closeScreen() {
        if (state == State.UNSET || state == State.CALCULATE || state == State.EXEC) return;
        cancel();
    }

    public static void tick() {
        if (state == State.UNSET || state == State.SELECT) return;

        Minecraft client = Minecraft.getInstance();
        MultiPlayerGameMode manager = client.gameMode;
        LocalPlayer player = client.player;
        if (player == null || manager == null) return;
        AbstractContainerMenu handler = player.containerMenu;

        if (state == State.CALCULATE) {
            if (search.working()) {
                current = search.peek();
                return;
            }

            FilledShape m = search.getBestShape();
            enchantCost = search.getMinCost();

            if (m == null) {
                cancel();
                showText("Couldn't find a tree", Colors.RED);
                if (!(client.gui.screen() instanceof AnvilScreen)) {
                    player.sendSystemMessage(Component.literal("Couldn't find a tree").withStyle(ChatFormatting.RED));
                }
                return;
            }

            readyToEnchant = true;
            operations = m.execute();

            ready();
            if (!(client.gui.screen() instanceof AnvilScreen)) {
                player.sendSystemMessage(Component.literal("Can enchant for " + enchantCost + " lvls").withStyle(ChatFormatting.GREEN));
            }

        } else if (state == State.EXEC) {
            if (!(client.gui.screen() instanceof AnvilScreen)) {
                sopid = 0;
                return;
            }

            if (timer < 5) {
                timer++;
                return;
            }
            timer = 0;

            AnvilItem current = operations.get(opid);
            int cost = current.result().cost() - current.sacrifice().cost() - current.target().cost();
            if (!player.hasInfiniteMaterials() && player.experienceLevel < cost) {
                showText("Not enough levels for the current combo, needed: " + cost, Colors.RED);
                return;
            }

            if (sopid == 0) {
                EnchantedItem item = current.target();
                if (item.matches(handler.getSlot(0).getItem())) {
                    sopid = 1;
                    return;
                }
                int slot = IntStream.range(3, handler.getItems().size()).boxed().filter(i -> item.matches(handler.getSlot(i).getItem())).findFirst().orElse(-1);
                if (slot == -1) return;
                manager.handleContainerInput(handler.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);

            } else if (sopid == 1) {
                EnchantedItem item = current.sacrifice();
                if (item.matches(handler.getSlot(1).getItem())) {
                    sopid = 2;
                    return;
                }
                int slot = IntStream.range(3, handler.getItems().size()).boxed().filter(i -> item.matches(handler.getSlot(i).getItem())).findFirst().orElse(-1);
                if (slot == -1) return;
                manager.handleContainerInput(handler.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);

            } else if (sopid == 2) {
                EnchantedItem item = current.result();
                if (!item.matches(handler.getSlot(2).getItem())) {
                    return;
                }
                manager.handleContainerInput(handler.containerId, 2, 0, ContainerInput.QUICK_MOVE, player);

                timer = -10;
                opid++;
                sopid = 0;
                if (opid == operations.size()) {
                    cancel();
                    showText("Done", Colors.GREEN);
                }
            }
        }
    }

    private static int opid = 0;
    private static int sopid = 0;
    private static int timer = 0;


    private static void showText(String text, int color) {
        textDisplay.setMessage(Component.literal(text).setStyle(Style.EMPTY.withColor(color)));
        textDisplay.visible = true;
    }

    private static void hideText() {
        textDisplay.visible = false;
    }

    public static Shape getShape() {
        if (state != State.CALCULATE || current == null) return null;
        return current.current();
    }

    public static double getProgress() {
        if (state != State.CALCULATE) return -1;
        if (current == null) return 0;
        return (double) current.index() / current.total();
    }

    public static State getState() {
        return state;
    }

    public enum State {UNSET, SELECT, CALCULATE, EXEC}

    public static List<Integer> getSelected() {
        if (state != State.SELECT) return Collections.emptyList();
        return selected;
    }

    public static void toggleSelection(int slot) {
        if (selected.contains(slot)) selected.remove((Object) slot);
        else selected.add(slot);
    }
}
