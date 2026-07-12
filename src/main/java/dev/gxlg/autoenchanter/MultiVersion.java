package dev.gxlg.autoenchanter;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MultiVersion {
	private static final Map<Set<Holder<Enchantment>>, Boolean> cacheCombine = new HashMap<>();

	public static boolean canCombine(Holder<Enchantment> a, Holder<Enchantment> b) {
		Set<Holder<Enchantment>> pair = Set.of(a, b);
		return cacheCombine.computeIfAbsent(pair, ignored -> Enchantment.areCompatible(a, b));
	}
}
