package dev.gxlg.autoenchanter.mixin;

import dev.gxlg.autoenchanter.Colors;
import dev.gxlg.autoenchanter.DataStructures;
import dev.gxlg.autoenchanter.Worker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilScreen.class)
public abstract class AnvilMixin extends ItemCombinerScreen<AnvilMenu> {

	public AnvilMixin(AnvilMenu menu, Inventory playerInventory, Component title, Identifier texture) {
		super(menu, playerInventory, title, texture);
	}

	@Inject(at = @At("TAIL"), method = "subInit")
	private void setup(CallbackInfo ci) {
		assert minecraft != null;
		StringWidget text = new StringWidget(20, 20, width - 40, 20, Component.empty(), font);
		this.addRenderableWidget(text);

		Button buttonSelect = Button.builder(Component.literal("Select items"), b -> Worker.select()).bounds(20, 45, 100, 20).build();
		Button buttonCalculate = Button.builder(Component.literal("Calculate"), b -> Worker.calculate()).bounds(20, 45, 100, 20).build();
		Button buttonStart = Button.builder(Component.literal("Start enchanting"), b -> Worker.start()).bounds(20, 45, 100, 20).build();
		Button buttonCancel = Button.builder(Component.literal("Cancel"), b -> Worker.cancel()).bounds(20, 67, 100, 20).build();

		this.addRenderableWidget(buttonSelect);
		this.addRenderableWidget(buttonCalculate);
		this.addRenderableWidget(buttonStart);
		this.addRenderableWidget(buttonCancel);

		Worker.setup(text, buttonSelect, buttonCalculate, buttonStart, buttonCancel);
	}

	@Inject(at = @At("RETURN"), method = "extractErrorIcon")
	private void drawInvalidRecipeArrow(GuiGraphicsExtractor graphics, int xs, int ys, CallbackInfo info) {
		boolean first = true;
		for (int slot : Worker.getSelected()) {
			int x = xs + menu.slots.get(slot).x;
			int y = ys + menu.slots.get(slot).y;
			graphics.fill(x, y, x + 16, y + 16, first ? Colors.BLUE : Colors.GREEN);
			first = false;
		}

		double progress = Worker.getProgress();
		if (progress >= 0) {
			graphics.fill(20, 41, 120, 43, 0xFF909090);
			graphics.fill(20, 41, (int) Math.round(progress * 100 + 20), 43, Colors.GREEN);
		}

		DataStructures.Shape shape = Worker.getShape();
		if (shape != null) {
			shape.draw(graphics, font, 10, 105, 128, 128);
		}
	}
}
