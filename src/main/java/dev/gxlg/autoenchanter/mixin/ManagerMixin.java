package dev.gxlg.autoenchanter.mixin;

import dev.gxlg.autoenchanter.Worker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class ManagerMixin {

	@Shadow
	private Minecraft minecraft;

	@Inject(at = @At("HEAD"), method = "handleContainerInput", cancellable = true)
	private void clickSlot(int syncId, int slotId, int button, ContainerInput actionType, Player player, CallbackInfo info) {
		if (Worker.getState() == Worker.State.SELECT) {
			if (slotId > 2 && (
					Worker.getSelected().isEmpty() ||
					Worker.getSelected().contains(slotId) ||
					player.containerMenu.getSlot(slotId).getItem().getItem() == Items.ENCHANTED_BOOK ||
					player.containerMenu.getSlot(slotId).getItem().getItem() == player.containerMenu.getSlot(Worker.getSelected().getFirst()).getItem().getItem()
			)) {
				Worker.toggleSelection(slotId);
			}
			info.cancel();
		}
	}

	@Inject(at = @At("HEAD"), method = "tick")
	private void tick(CallbackInfo info) {
		if (!(minecraft.gui.screen() instanceof AnvilScreen)) {
			Worker.closeScreen();
		}
		Worker.tick();
	}
}
